#!/bin/bash

# 撮合系统主备切换脚本
# 用法: ./ha_switch.sh [command] [options]

# 配置
PRIMARY_HOST="${PRIMARY_HOST:-"192.168.1.100"}"
STANDBY_HOST="${STANDBY_HOST:-"192.168.1.101"}"
MATCHING_PORT="${MATCHING_PORT:-"8080"}"
TIMEOUT="${TIMEOUT:-30}"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 日志函数
log_info() {
    echo -e "${GREEN}[INFO]${NC} $(date '+%Y-%m-%d %H:%M:%S') $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $(date '+%Y-%m-%d %H:%M:%S') $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $(date '+%Y-%m-%d %H:%M:%S') $1"
}

# HTTP请求函数
make_request() {
    local host=$1
    local method=$2
    local endpoint=$3
    local timeout=${4:-10}

    curl -s -m "$timeout" -X "$method" "http://$host:$MATCHING_PORT$endpoint" 2>/dev/null
}

# 检查实例状态
check_instance_status() {
    local host=$1
    local response

    response=$(make_request "$host" "GET" "/ops/ha/status" 5)

    if [ $? -eq 0 ] && echo "$response" | grep -q '"success":true'; then
        local role
        role=$(echo "$response" | grep -o '"role":"[^"]*"' | cut -d'"' -f4)
        local status
        status=$(echo "$response" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
        echo "$role:$status"
        return 0
    else
        echo "UNKNOWN:UNKNOWN"
        return 1
    fi
}

# 等待状态变更
wait_for_status() {
    local host=$1
    local expected_role=$2
    local expected_status=$3
    local max_wait=${4:-30}

    log_info "等待 $host 变为 $expected_role/$expected_status 状态..."

    local count=0
    while [ $count -lt $max_wait ]; do
        local current_status
        current_status=$(check_instance_status "$host")

        if [ "$current_status" = "$expected_role:$expected_status" ]; then
            log_info "$host 已变为 $expected_role/$expected_status 状态"
            return 0
        fi

        echo -n "."
        sleep 1
        count=$((count + 1))
    done

    echo
    log_error "等待超时: $host 未能变为 $expected_role/$expected_status 状态"
    return 1
}

# 检查服务健康状态
check_health() {
    local host=$1
    local response

    response=$(make_request "$host" "GET" "/actuator/health" 5)

    if [ $? -eq 0 ] && echo "$response" | grep -q '"status":"UP"'; then
        return 0
    else
        return 1
    fi
}

# 显示当前状态
show_status() {
    echo -e "${BLUE}=== 当前HA状态 ===${NC}"

    log_info "检查主实例 ($PRIMARY_HOST)..."
    local primary_status
    primary_status=$(check_instance_status "$PRIMARY_HOST")
    if [ $? -eq 0 ]; then
        echo -e "主实例: ${GREEN}$primary_status${NC}"
    else
        echo -e "主实例: ${RED}无法连接${NC}"
    fi

    log_info "检查备实例 ($STANDBY_HOST)..."
    local standby_status
    standby_status=$(check_instance_status "$STANDBY_HOST")
    if [ $? -eq 0 ]; then
        echo -e "备实例: ${GREEN}$standby_status${NC}"
    else
        echo -e "备实例: ${RED}无法连接${NC}"
    fi
}

# 主备切换（主实例故障，备实例接管）
failover() {
    log_info "开始主备切换（故障转移）..."

    # 1. 检查当前状态
    show_status
    echo

    # 2. 停用主实例
    log_info "停用主实例 ($PRIMARY_HOST)..."
    local response
    response=$(make_request "$PRIMARY_HOST" "POST" "/ops/ha/deactivate" "$TIMEOUT")

    if [ $? -eq 0 ] && echo "$response" | grep -q '"success":true'; then
        log_info "主实例已停用"
    else
        log_warn "主实例停用失败或无响应，继续执行..."
    fi
    fi

    # 3. 激活备实例
    log_info "激活备实例 ($STANDBY_HOST)..."
    response=$(make_request "$STANDBY_HOST" "POST" "/ops/ha/activate" "$TIMEOUT")

    if [ $? -eq 0 ] && echo "$response" | grep -q '"success":true'; then
        log_info "备实例激活请求已发送"
    else
        log_error "备实例激活失败"
        return 1
    fi

    # 4. 等待备实例变为主实例
    if wait_for_status "$STANDBY_HOST" "PRIMARY" "ACTIVE" "$TIMEOUT"; then
        log_info "故障转移成功!"

        # 5. 验证服务健康状态
        if check_health "$STANDBY_HOST"; then
            log_info "新主实例健康检查通过"
        else
            log_warn "新主实例健康检查失败"
        fi

        # 6. 显示最终状态
        echo
        show_status
        return 0
    else
        log_error "故障转移失败"
        return 1
    fi
}

# 主备切换（计划内切换）
switchover() {
    log_info "开始计划内主备切换..."

    # 1. 检查当前状态
    show_status
    echo

    # 2. 检查两个实例都健康
    if ! check_health "$PRIMARY_HOST"; then
        log_error "主实例健康检查失败，无法执行计划内切换"
        return 1
    fi

    if ! check_health "$STANDBY_HOST"; then
        log_error "备实例健康检查失败，无法执行计划内切换"
        return 1
    fi

    # 3. 停用当前主实例
    log_info "停用当前主实例 ($PRIMARY_HOST)..."
    local response
    response=$(make_request "$PRIMARY_HOST" "POST" "/ops/ha/deactivate" "$TIMEOUT")

    if [ $? -eq 0 ] && echo "$response" | grep -q '"success":true'; then
        log_info "主实例已停用"
    else
        log_error "主实例停用失败"
        return 1
    fi

    # 4. 等待主实例变为备实例
    if ! wait_for_status "$PRIMARY_HOST" "STANDBY" "STANDBY" 10; then
        log_error "主实例未能正确切换为备实例"
        return 1
    fi

    # 5. 激活新主实例
    log_info "激活新主实例 ($STANDBY_HOST)..."
    response=$(make_request "$STANDBY_HOST" "POST" "/ops/ha/activate" "$TIMEOUT")

    if [ $? -eq 0 ] && echo "$response" | grep -q '"success":true'; then
        log_info "新主实例激活请求已发送"
    else
        log_error "新主实例激活失败"
        return 1
    fi

    # 6. 等待新主实例变为活跃状态
    if wait_for_status "$STANDBY_HOST" "PRIMARY" "ACTIVE" "$TIMEOUT"; then
        log_info "计划内切换成功!"

        # 7. 验证服务健康状态
        if check_health "$STANDBY_HOST"; then
            log_info "新主实例健康检查通过"
        else
            log_warn "新主实例健康检查失败"
        fi

        # 8. 显示最终状态
        echo
        show_status

        # 9. 交换主备角色变量（用于后续操作）
        local temp=$PRIMARY_HOST
        PRIMARY_HOST=$STANDBY_HOST
        STANDBY_HOST=$temp

        log_info "主备角色已交换: 新主实例=$PRIMARY_HOST, 新备实例=$STANDBY_HOST"
        return 0
    else
        log_error "计划内切换失败"
        return 1
    fi
}

# 回切（切换回原来的主实例）
switchback() {
    log_info "开始回切到原主实例..."

    # 交换主备角色后执行切换
    local temp=$PRIMARY_HOST
    PRIMARY_HOST=$STANDBY_HOST
    STANDBY_HOST=$temp

    switchover
}

# 强制激活
force_activate() {
    local host=$1
    if [ -z "$host" ]; then
        log_error "请指定要激活的主机"
        return 1
    fi

    log_info "强制激活 ($host)..."
    local response
    response=$(make_request "$host" "POST" "/ops/ha/activate" "$TIMEOUT")

    if [ $? -eq 0 ] && echo "$response" | grep -q '"success":true'; then
        log_info "强制激活成功"
        if wait_for_status "$host" "PRIMARY" "ACTIVE" "$TIMEOUT"; then
            log_info "实例已成功激活为主实例"
            return 0
        else
            log_error "实例激活后状态异常"
            return 1
        fi
    else
        log_error "强制激活失败"
        return 1
    fi
}

# 健康检查
health_check() {
    echo -e "${BLUE}=== 健康检查 ===${NC}"

    log_info "检查主实例健康状态..."
    if check_health "$PRIMARY_HOST"; then
        echo -e "主实例 ($PRIMARY_HOST): ${GREEN}健康${NC}"
    else
        echo -e "主实例 ($PRIMARY_HOST): ${RED}异常${NC}"
    fi

    log_info "检查备实例健康状态..."
    if check_health "$STANDBY_HOST"; then
        echo -e "备实例 ($STANDBY_HOST): ${GREEN}健康${NC}"
    else
        echo -e "备实例 ($STANDBY_HOST): ${RED}异常${NC}"
    fi
}

# 显示帮助
show_help() {
    cat << EOF
撮合系统主备切换脚本
用法: $0 [COMMAND] [OPTIONS]

命令:
  status                显示当前HA状态
  failover              故障转移（主实例故障时使用）
  switchover            计划内切换（主备角色互换）
  switchback            回切到原主实例
  force-activate HOST   强制激活指定实例为主实例
  health                健康检查

选项:
  -h, --help            显示帮助信息
  --primary HOST        指定主实例地址（默认: 192.168.1.100）
  --standby HOST        指定备实例地址（默认: 192.168.1.101）
  --port PORT           指定服务端口（默认: 8080）
  --timeout SECONDS     指定超时时间（默认: 30）

示例:
  $0 status             # 查看状态
  $0 failover           # 故障转移
  $0 switchover         # 计划内切换
  $0 force-activate 192.168.1.101  # 强制激活备实例
  $0 --primary 10.0.1.100 --standby 10.0.1.101 status

环境变量:
  PRIMARY_HOST          主实例地址
  STANDBY_HOST          备实例地址
  MATCHING_PORT         服务端口
  TIMEOUT               超时时间

切换流程说明:
  故障转移 (failover):   主实例故障 → 停用主实例 → 激活备实例
  计划内切换 (switchover): 停用主实例 → 激活备实例 → 角色互换
  回切 (switchback):     切换回原来的主实例
EOF
}

# 解析命令行参数
while [[ $# -gt 0 ]]; do
    case $1 in
        --primary)
            PRIMARY_HOST="$2"
            shift 2
            ;;
        --standby)
            STANDBY_HOST="$2"
            shift 2
            ;;
        --port)
            MATCHING_PORT="$2"
            shift 2
            ;;
        --timeout)
            TIMEOUT="$2"
            shift 2
            ;;
        *)
            break
            ;;
    esac
done

# 主函数
main() {
    case "$1" in
        "status")
            show_status
            ;;
        "failover")
            failover
            ;;
        "switchover")
            switchover
            ;;
        "switchback")
            switchback
            ;;
        "force-activate")
            force_activate "$2"
            ;;
        "health")
            health_check
            ;;
        "-h"|"--help"|"help")
            show_help
            ;;
        *)
            log_error "未知命令: $1"
            show_help
            exit 1
            ;;
    esac
}

main "$@"