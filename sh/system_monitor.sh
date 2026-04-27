#!/bin/bash

# 撮合系统监控脚本
# 用法: ./system_monitor.sh [options]

# 配置
MATCHING_HOST="${MATCHING_HOST:-"localhost"}"
MATCHING_PORT="${MATCHING_PORT:-"8080"}"
BASE_URL="http://${MATCHING_HOST}:${MATCHING_PORT}"
LOG_FILE="/tmp/matching_monitor.log"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# 日志函数
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1" | tee -a "$LOG_FILE"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1" | tee -a "$LOG_FILE"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" | tee -a "$LOG_FILE"
}

# HTTP请求函数
make_request() {
    local endpoint=$1
    curl -s "$BASE_URL$endpoint" 2>/dev/null
}

# 格式化JSON
format_json() {
    if command -v jq &>/dev/null; then
        echo "$1" | jq .
    else
        echo "$1"
    fi
}

# 格式化字节数
format_bytes() {
    local bytes=$1
    if [ "$bytes" -ge 1073741824 ]; then
        echo "$(echo "scale=2; $bytes/1073741824" | bc)GB"
    elif [ "$bytes" -ge 1048576 ]; then
        echo "$(echo "scale=2; $bytes/1048576" | bc)MB"
    elif [ "$bytes" -ge 1024 ]; then
        echo "$(echo "scale=2; $bytes/1024" | bc)KB"
    else
        echo "${bytes}B"
    fi
}

# 检查服务健康状态
check_health() {
    local response
    response=$(make_request "/actuator/health" 2>/dev/null)

    if [ $? -eq 0 ] && echo "$response" | grep -q '"status":"UP"'; then
        echo -e "${GREEN}✓${NC} 服务健康"
        return 0
    else
        echo -e "${RED}✗${NC} 服务异常"
        return 1
    fi
}

# 检查HA状态
check_ha_status() {
    local response
    response=$(make_request "/ops/ha/status")

    if [ $? -eq 0 ]; then
        local role
        role=$(echo "$response" | grep -o '"role":"[^"]*"' | cut -d'"' -f4)
        local status
        status=$(echo "$response" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)

        if [ "$role" = "PRIMARY" ] && [ "$status" = "ACTIVE" ]; then
            echo -e "${GREEN}✓${NC} HA状态: ${GREEN}PRIMARY/ACTIVE${NC}"
        elif [ "$role" = "STANDBY" ]; then
            echo -e "${YELLOW}⚠${NC} HA状态: ${YELLOW}STANDBY${NC}"
        else
            echo -e "${RED}✗${NC} HA状态: ${RED}$role/$status${NC}"
        fi
    else
        echo -e "${RED}✗${NC} HA状态: 无法获取"
    fi
}

# 检查交易对状态
check_symbols() {
    local response
    response=$(make_request "/ops/symbols")

    if [ $? -eq 0 ]; then
        echo -e "${BLUE}=== 交易对状态 ===${NC}"
        if command -v jq &>/dev/null; then
            echo "$response" | jq -r '.data[] | "\(.symbolId): 订单数=\(.orderCount), 买盘=\(.buyLevels), 卖盘=\(.sellLevels), 交易=\(.tradingEnabled)"' | while read -r line; do
                if echo "$line" | grep -q "交易=开启"; then
                    echo -e "${GREEN}✓${NC} $line"
                else
                    echo -e "${YELLOW}⚠${NC} $line"
                fi
            done
        else
            format_json "$response"
        fi
    else
        echo -e "${RED}✗${NC} 无法获取交易对状态"
    fi
}

# 检查Chronicle Queue状态
check_chronicle_status() {
    echo -e "${BLUE}=== Chronicle Queue 状态 ===${NC}"
    local symbols=("BTCUSDT" "ETHUSDT" "LTCUSDT" "BCHUSDT")

    for symbol in "${symbols[@]}"; do
        local size_response
        size_response=$(make_request "/ops/chronicle/symbol/size")

        if [ $? -eq 0 ] && echo "$size_response" | grep -q '"success":true'; then
            if command -v jq &>/dev/null; then
                local total_size
                total_size=$(echo "$size_response" | jq -r '.data.totalSize // 0')
                local file_count
                file_count=$(echo "$size_response" | jq -r '.data.fileCount // 0')
                echo -e "${GREEN}✓${NC} $symbol: $(format_bytes $total_size), $file_count 文件"
            else
                echo -e "${GREEN}✓${NC} $symbol: 正常"
            fi
        else
            echo -e "${YELLOW}⚠${NC} $symbol: 无数据"
        fi
    done
}

# 检查清理状态
check_cleanup_status() {
    local response
    response=$(make_request "/ops/chronicle/cleanup/status")

    if [ $? -eq 0 ]; then
        echo -e "${BLUE}=== 清理状态 ===${NC}"
        if command -v jq &>/dev/null; then
            local running
            running=$(echo "$response" | jq -r '.data.running // false')
            local total_deleted
            total_deleted=$(echo "$response" | jq -r '.data.totalFilesDeleted // 0')
            local total_freed
            total_freed=$(echo "$response" | jq -r '.data.totalSpaceFreed // 0')

            if [ "$running" = "true" ]; then
                echo -e "${YELLOW}⚠${NC} 清理状态: 运行中${NC}"
            else
                echo -e "${GREEN}✓${NC} 清理状态: 空闲${NC}"
            fi
            echo -e "${CYAN}ℹ${NC} 总计删除: $total_deleted 文件, $(format_bytes $total_freed)"
        fi
        format_json "$response"
    else
        echo -e "${RED}✗${NC} 无法获取清理状态"
    fi
}

# 检查系统资源
check_system_resources() {
    echo -e "${BLUE}=== 系统资源 ===${NC}"

    # CPU使用率
    if command -v top &>/dev/null; then
        local cpu_usage
        cpu_usage=$(top -l 1 -n 6 | grep "CPU usage" | awk '{print $3}' | sed 's/%//')
        if [ -n "$cpu_usage" ]; then
            if (( $(echo "$cpu_usage > 80" | bc -l) )); then
                echo -e "${RED}✗${NC} CPU使用率: ${RED}${cpu_usage}%${NC}"
            elif (( $(echo "$cpu_usage > 60" | bc -l) )); then
                echo -e "${YELLOW}⚠${NC} CPU使用率: ${YELLOW}${cpu_usage}%${NC}"
            else
                echo -e "${GREEN}✓${NC} CPU使用率: ${GREEN}${cpu_usage}%${NC}"
            fi
        fi
    fi

    # 内存使用率
    if command -v free &>/dev/null; then
        local mem_info
        mem_info=$(free | grep Mem)
        local total
        total=$(echo "$mem_info" | awk '{print $2}')
        local used
        used=$(echo "$mem_info" | awk '{print $3}')
        local usage
        usage=$(echo "scale=1; $used*100/$total" | bc)
        if (( $(echo "$usage > 80" | bc -l) )); then
            echo -e "${RED}✗${NC} 内存使用率: ${RED}${usage}%${NC}"
        elif (( $(echo "$usage > 60" | bc -l) )); then
            echo -e "${YELLOW}⚠${NC} 内存使用率: ${YELLOW}${usage}%${NC}"
        else
            echo -e "${GREEN}✓${NC} 内存使用率: ${GREEN}${usage}%${NC}"
        fi
    fi

    # 磁盘使用率
    local disk_usage
    disk_usage=$(df -h / | tail -1 | awk '{print $5}' | sed 's/%//')
    if [ "$disk_usage" -gt 80 ]; then
        echo -e "${RED}✗${NC} 磁盘使用率: ${RED}${disk_usage}%${NC}"
    elif [ "$disk_usage" -gt 60 ]; then
        echo -e "${YELLOW}⚠${NC} 磁盘使用率: ${YELLOW}${disk_usage}%${NC}"
    else
        echo -e "${GREEN}✓${NC} 磁盘使用率: ${GREEN}${disk_usage}%${NC}"
    fi
}

# 检查网络连接
check_network() {
    echo -e "${BLUE}=== 网络连接 ===${NC}"

    # 检查端口监听
    if netstat -ln | grep -q ":$MATCHING_PORT "; then
        echo -e "${GREEN}✓${NC} 端口 $MATCHING_PORT 正在监听"
    else
        echo -e "${RED}✗${NC} 端口 $MATCHING_PORT 未监听"
    fi

    # 检查Redis连接
    local redis_response
    redis_response=$(make_request "/ops/redis/info")
    if [ $? -eq 0 ] && echo "$redis_response" | grep -q '"success":true'; then
        echo -e "${GREEN}✓${NC} Redis连接正常"
    else
        echo -e "${RED}✗${NC} Redis连接异常"
    fi
}

# 生成完整报告
generate_report() {
    local timestamp
    timestamp=$(date '+%Y-%m-%d %H:%M:%S')

    echo "================================================"
    echo "撮合系统监控报告"
    echo "时间: $timestamp"
    echo "主机: $MATCHING_HOST:$MATCHING_PORT"
    echo "================================================"
    echo

    check_health
    echo

    check_ha_status
    echo

    check_symbols
    echo

    check_chronicle_status
    echo

    check_cleanup_status
    echo

    check_system_resources
    echo

    check_network
    echo

    echo "================================================"
}

# 实时监控
monitor_realtime() {
    log_info "开始实时监控 (按 Ctrl+C 退出)..."
    while true; do
        clear
        generate_report
        echo
        echo "=== 5秒后刷新 ==="
        sleep 5
    done
}

# 告警检查
check_alerts() {
    local alerts=()

    # 检查服务健康
    if ! check_health >/dev/null 2>&1; then
        alerts+=("服务健康检查失败")
    fi

    # 检查磁盘空间
    local disk_usage
    disk_usage=$(df -h / | tail -1 | awk '{print $5}' | sed 's/%//')
    if [ "$disk_usage" -gt 85 ]; then
        alerts+=("磁盘使用率过高: ${disk_usage}%")
    fi

    # 检查Chronicle Queue大小
    local symbols=("BTCUSDT" "ETHUSDT")
    for symbol in "${symbols[@]}"; do
        local size_response
        size_response=$(make_request "/ops/chronicle/symbol/size")
        if [ $? -eq 0 ] && command -v jq &>/dev/null; then
            local total_size
            total_size=$(echo "$size_response" | jq -r '.data.totalSize // 0')
            # 如果单个交易对超过10GB
            if [ "$total_size" -gt 10737418240 ]; then
                alerts+=("$symbol Chronicle Queue过大: $(format_bytes $total_size)")
            fi
        fi
    done

    # 输出告警
    if [ ${#alerts[@]} -gt 0 ]; then
        echo -e "${RED}=== 告警信息 ===${NC}"
        for alert in "${alerts[@]}"; do
            echo -e "${RED}⚠${NC} $alert"
        done
        return 1
    else
        echo -e "${GREEN}✓${NC} 无告警信息"
        return 0
    fi
}

# 显示帮助
show_help() {
    cat << EOF
撮合系统监控脚本
用法: $0 [COMMAND] [OPTIONS]

命令:
  report                生成完整监控报告
  monitor               实时监控（默认）
  health                检查服务健康状态
  ha                    检查HA状态
  symbols               检查交易对状态
  chronicle             检查Chronicle Queue状态
  cleanup               检查清理状态
  system                检查系统资源
  network               检查网络连接
  alerts                检查告警信息

选项:
  -h, --help            显示帮助信息
  --host HOST           指定服务器地址（默认: localhost）
  --port PORT           指定服务器端口（默认: 8080）
  --log FILE            指定日志文件（默认: /tmp/matching_monitor.log）

示例:
  $0                    # 实时监控
  $0 report             # 生成报告
  $0 alerts             # 检查告警
  $0 --host 192.168.1.100 --port 8080 monitor

环境变量:
  MATCHING_HOST         服务器地址
  MATCHING_PORT         服务器端口
EOF
}

# 主函数
main() {
    case "$1" in
        "report")
            generate_report
            ;;
        "monitor"|"")
            monitor_realtime
            ;;
        "health")
            check_health
            ;;
        "ha")
            check_ha_status
            ;;
        "symbols")
            check_symbols
            ;;
        "chronicle")
            check_chronicle_status
            ;;
        "cleanup")
            check_cleanup_status
            ;;
        "system")
            check_system_resources
            ;;
        "network")
            check_network
            ;;
        "alerts")
            check_alerts
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

# 解析命令行参数
while [[ $# -gt 0 ]]; do
    case $1 in
        --host)
            MATCHING_HOST="$2"
            BASE_URL="http://${MATCHING_HOST}:${MATCHING_PORT}"
            shift 2
            ;;
        --port)
            MATCHING_PORT="$2"
            BASE_URL="http://${MATCHING_HOST}:${MATCHING_PORT}"
            shift 2
            ;;
        --log)
            LOG_FILE="$2"
            shift 2
            ;;
        *)
            break
            ;;
    esac
done

# 创建日志文件
touch "$LOG_FILE"

# 执行主函数
main "$@"