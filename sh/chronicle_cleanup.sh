#!/bin/bash

# Chronicle Queue 清理管理脚本
# 用法: ./chronicle_cleanup.sh [command] [options]

# 配置
MATCHING_HOST=${MATCHING_HOST:-"localhost"}
MATCHING_PORT=${MATCHING_PORT:-"8080"}
BASE_URL="http://${MATCHING_HOST}:${MATCHING_PORT}/ops/chronicle"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 日志函数
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_debug() {
    echo -e "${BLUE}[DEBUG]${NC} $1"
}

# HTTP请求函数
make_request() {
    local method=$1
    local endpoint=$2
    local data=$3

    if [ -n "$data" ]; then
        curl -s -X "$method" \
            -H "Content-Type: application/json" \
            -d "$data" \
            "$BASE_URL$endpoint"
    else
        curl -s -X "$method" "$BASE_URL$endpoint"
    fi
}

# 格式化输出JSON
format_json() {
    if command -v jq &>/dev/null; then
        echo "$1" | jq .
    else
        echo "$1"
    fi
}

# 显示帮助信息
show_help() {
    cat << EOF
Chronicle Queue 清理管理脚本
用法: $0 [COMMAND] [OPTIONS]

命令:
  cleanup-all          清理所有交易对
  cleanup <SYMBOL>     清理指定交易对
  status               查看清理状态
  status <SYMBOL>      查看指定交易对清理状态
  preview <SYMBOL>     预览清理操作（不实际删除）
  stop                 停止正在运行的清理
  checkpoint <SYMBOL>  为指定交易对创建检查点
  size <SYMBOL>        查看指定交易对存储大小
  health <SYMBOL>      检查指定交易对健康状态
  config               查看清理配置
  config-update        更新清理配置
  monitor              监控清理状态（实时）

选项:
  -h, --help           显示帮助信息
  -v, --verbose        详细输出
  -q, --quiet          静默模式
  --host HOST          指定服务器地址（默认：localhost）
  --port PORT          指定服务器端口（默认：8080）

示例:
  $0 cleanup-all               # 清理所有交易对
  $0 cleanup BTCUSDT           # 清理BTC/USDT
EOF
}

# 清理所有交易对
cleanup_all() {
    log_info "开始批量清理所有交易对..."
    response=$(make_request "POST" "/cleanup/all")
    if echo "$response" | grep -q '"success":true'; then
        log_info "所有清理任务已启动"
        format_json "$response"
    else
        log_error "批量清理失败"
        format_json "$response"
        return 1
    fi
}

# 清理指定交易对
cleanup_symbol() {
    local symbol=$1
    if [ -z "$symbol" ]; then
        log_error "请指定交易对"
        return 1
    fi

    log_info "开始清理交易对: $symbol"
    response=$(make_request "POST" "/cleanup/$symbol")
    if echo "$response" | grep -q '"success":true'; then
        log_info "清理任务已启动"
        format_json "$response"
    else
        log_error "清理启动失败"
        format_json "$response"
        return 1
    fi
}

# 查看清理状态
show_status() {
    local symbol=$1
    if [ -n "$symbol" ]; then
        log_info "查看交易对 $symbol 的清理状态..."
        response=$(make_request "GET" "/cleanup/$symbol/status")
    else
        log_info "查看整体清理状态..."
        response=$(make_request "GET" "/cleanup/status")
    fi
    format_json "$response"
}

# 预览清理
preview_cleanup() {
    local symbol=$1
    if [ -z "$symbol" ]; then
        log_error "请指定交易对"
        return 1
    fi

    log_info "预览交易对 $symbol 的清理操作..."
    response=$(make_request "GET" "/cleanup/$symbol/preview")
    format_json "$response"
}

# 停止清理
stop_cleanup() {
    log_info "停止清理任务..."
    response=$(make_request "POST" "/cleanup/stop")
    if echo "$response" | grep -q '"success":true'; then
        log_info "清理任务已停止"
    else
        log_error "停止清理失败"
    fi
    format_json "$response"
}

# 创建检查点
create_checkpoint() {
    local symbol=$1
    if [ -z "$symbol" ]; then
        log_error "请指定交易对"
        return 1
    fi

    log_info "为交易对 $symbol 创建检查点..."
    response=$(make_request "POST" "/$symbol/checkpoint")
    format_json "$response"
}

# 查看存储大小
show_size() {
    local symbol=$1
    if [ -z "$symbol" ]; then
        log_error "请指定交易对"
        return 1
    fi

    log_info "查看交易对 $symbol 的存储大小..."
    response=$(make_request "GET" "/$symbol/size")
    format_json "$response"
}

# 健康检查
health_check() {
    local symbol=$1
    if [ -z "$symbol" ]; then
        log_error "请指定交易对"
        return 1
    fi

    log_info "检查交易对 $symbol 的健康状态..."
    response=$(make_request "GET" "/$symbol/health")
    format_json "$response"
}

# 查看配置
show_config() {
    log_info "查看清理配置..."
    response=$(make_request "GET" "/config")
    format_json "$response"
}

# 更新配置
update_config() {
    # 示例配置
    config='{
        "retentionDays": 7,
        "maxSizeGB": 100,
        "keepRecentFiles": 3,
        "enabled": true
    }'

    echo "请输入新的配置（JSON格式）:"
    read -r user_config
    if [ -n "$user_config" ]; then
        config="$user_config"
    fi

    response=$(make_request "POST" "/config" "$config")
    format_json "$response"
}

# 监控清理状态
monitor_cleanup() {
    log_info "开始监控清理状态（按 Ctrl+C 退出）..."
    while true; do
        clear
        echo "=== Chronicle Queue 清理状态监控 ==="
        echo "时间: $(date)"
        echo
        response=$(make_request "GET" "/cleanup/status")
        format_json "$response"
        echo
        echo "=== 5秒后刷新 ==="
        sleep 5
    done
}

# 批量操作
batch_cleanup() {
    local symbols=("BTCUSDT" "ETHUSDT" "LTCUSDT" "BCHUSDT")
    log_info "开始批量清理交易对..."
    for symbol in "${symbols[@]}"; do
        log_info "清理 $symbol..."
        cleanup_symbol "$symbol"
        sleep 2
    done
}

# 清理报告
cleanup_report() {
    log_info "生成清理报告..."
    echo "=== Chronicle Queue 清理报告 ==="
    echo "生成时间: $(date)"
    echo

    # 整体状态
    echo "=== 整体状态 ==="
    show_status
    echo

    # 各交易对状态
    echo "=== 各交易对状态 ==="
    local symbols=("BTCUSDT" "ETHUSDT" "LTCUSDT" "BCHUSDT")
    for symbol in "${symbols[@]}"; do
        echo "--- $symbol ---"
        show_status "$symbol"
        echo
    done
}

# 主函数
main() {
    case "$1" in
        "cleanup-all")
            cleanup_all
            ;;
        "cleanup")
            cleanup_symbol "$2"
            ;;
        "status")
            show_status "$2"
            ;;
        "preview")
            preview_cleanup "$2"
            ;;
        "stop")
            stop_cleanup
            ;;
        "checkpoint")
            create_checkpoint "$2"
            ;;
        "size")
            show_size "$2"
            ;;
        "health")
            health_check "$2"
            ;;
        "config")
            show_config
            ;;
        "config-update")
            update_config
            ;;
        "monitor")
            monitor_cleanup
            ;;
        "batch")
            batch_cleanup
            ;;
        "report")
            cleanup_report
            ;;
        "-h"|"--help"|"help")
            show_help
            ;;
        "")
            log_error "请指定命令"
            show_help
            exit 1
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
            BASE_URL="http://${MATCHING_HOST}:${MATCHING_PORT}/ops/chronicle"
            shift 2
            ;;
        --port)
            MATCHING_PORT="$2"
            BASE_URL="http://${MATCHING_HOST}:${MATCHING_PORT}/ops/chronicle"
            shift 2
            ;;
        -v|--verbose)
            set -x
            shift
            ;;
        -q|--quiet)
            exec >/dev/null 2>&1
            shift
            ;;
        *)
            break
            ;;
    esac
done

main "$@"