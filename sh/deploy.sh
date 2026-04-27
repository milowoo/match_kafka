#!/bin/bash

# ===================== 配置 =====================
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
PROJECT_DIR=$(dirname "$SCRIPT_DIR")
BUILD_DIR="$PROJECT_DIR/target"
DEPLOY_DIR="/opt/matching"
SERVICE_NAME="matching"
JAR_NAME="matching.jar"

declare -A ENV_CONFIGS
ENV_CONFIGS[dev]="application-dev.properties"
ENV_CONFIGS[test]="application-test.properties"
ENV_CONFIGS[prod]="application-prod.properties"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
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

# ===================== 依赖检查 =====================
check_dependencies() {
    log_info "检查系统依赖..."

    # 检查 Java
    if ! command -v java &>/dev/null; then
        log_error "Java未安装，请先安装JDK 21"
        exit 1
    fi

    local java_version
    java_version=$(java -version 2>&1 | head -n1 | cut -d'"' -f2 | cut -d'.' -f2)
    if [ "$java_version" -lt 21 ]; then
        log_error "Java版本过低，需要JDK 21或更高版本"
        exit 1
    fi
    log_info "Java版本检查通过: $(java -version 2>&1 | head -n1)"

    # 检查 Maven
    if ! command -v mvn &>/dev/null; then
        log_error "Maven未安装，请先安装Maven"
        exit 1
    fi
    log_info "Maven版本: $(mvn -version | head -n1)"

    # 检查 systemctl (用于服务管理)
    if ! command -v systemctl &>/dev/null; then
        log_warn "systemctl未找到，将使用传统方式管理服务"
    fi
}

# ===================== 构建项目 =====================
build_project() {
    log_info "开始构建项目..."
    cd "$PROJECT_DIR" || exit 1

    # 清理并编译
    if mvn clean package -DskipTests; then
        log_info "项目构建成功"
    else
        log_error "项目构建失败"
        exit 1
    fi

    # 检查JAR文件
    if [ ! -f "$BUILD_DIR/$JAR_NAME" ]; then
        log_error "JAR文件未找到: $BUILD_DIR/$JAR_NAME"
        exit 1
    fi
    log_info "JAR文件大小: $(du -h "$BUILD_DIR/$JAR_NAME" | cut -f1)"
}

# ===================== 创建部署目录 =====================
create_deploy_dirs() {
    log_info "创建部署目录..."
    sudo mkdir -p "$DEPLOY_DIR"/{bin,config,logs,data,scripts}
    sudo mkdir -p /var/log/matching
    sudo mkdir -p /data/matching/eventlog-queue

    # 设置权限
    sudo chown -R "$USER:$USER" "$DEPLOY_DIR"
    sudo chown -R "$USER:$USER" /var/log/matching
    sudo chown -R "$USER:$USER" /data/matching

    log_info "部署目录创建完成"
}

# ===================== 部署应用 =====================
deploy_application() {
    local env=$1
    log_info "部署应用到 $env 环境..."

    # 停止现有服务
    stop_service

    # 备份旧版本
    if [ -f "$DEPLOY_DIR/bin/$JAR_NAME" ]; then
        local backup_name="$JAR_NAME.$(date +%Y%m%d_%H%M%S).bak"
        log_info "备份旧版本: $backup_name"
        mv "$DEPLOY_DIR/bin/$JAR_NAME" "$DEPLOY_DIR/bin/$backup_name"
    fi

    # 复制新版本
    cp "$BUILD_DIR/$JAR_NAME" "$DEPLOY_DIR/bin/"

    # 复制配置文件
    if [ -f "$PROJECT_DIR/src/main/resources/${ENV_CONFIGS[$env]}" ]; then
        cp "$PROJECT_DIR/src/main/resources/${ENV_CONFIGS[$env]}" "$DEPLOY_DIR/config/application.properties"
        log_info "配置文件已复制: ${ENV_CONFIGS[$env]}"
    else
        cp "$PROJECT_DIR/src/main/resources/application.properties" "$DEPLOY_DIR/config/"
        log_info "使用默认配置文件"
    fi

    # 复制脚本
    cp "$SCRIPT_DIR"/*.sh "$DEPLOY_DIR/scripts/"
    chmod +x "$DEPLOY_DIR/scripts"/*.sh

    log_info "应用部署完成"
}

# ===================== 创建 systemd 服务 =====================
create_systemd_service() {
    local env=$1
    log_info "创建systemd服务..."

    cat > /tmp/matching.service << EOF
[Unit]
Description=Matching Engine Service
After=network.target

[Service]
Type=simple
User=$USER
Group=$USER
WorkingDirectory=$DEPLOY_DIR
ExecStart=/usr/bin/java \$JAVA_OPTS -jar $DEPLOY_DIR/bin/$JAR_NAME --spring.config.location=$DEPLOY_DIR/config/application.properties
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal
SysLogIdentifier=matching

# JVM参数
Environment="JAVA_OPTS=-Xms2g -Xmx4g -XX:+UseG1GC -XX:+UseStringDeduplication"

# 应用参数
Environment="SPRING_PROFILES_ACTIVE=$env"
Environment="MATCHING_ENV=$env"

[Install]
WantedBy=multi-user.target
EOF

    sudo mv /tmp/matching.service /etc/systemd/system/
    sudo systemctl daemon-reload
    sudo systemctl enable matching

    log_info "systemd服务创建完成"
}

# ===================== 启动服务 =====================
start_service() {
    log_info "启动服务..."
    if command -v systemctl &>/dev/null; then
        sudo systemctl start matching
        sleep 5
        if sudo systemctl is-active --quiet matching; then
            log_info "服务启动成功"
        else
            log_error "服务启动失败"
            sudo systemctl status matching
            exit 1
        fi
    else
        # 传统方式启动
        nohup java -jar "$DEPLOY_DIR/bin/$JAR_NAME" \
            --spring.config.location="$DEPLOY_DIR/config/application.properties" \
            > "$DEPLOY_DIR/logs/matching.log" 2>&1 &
        echo $! > "$DEPLOY_DIR/matching.pid"
        log_info "服务已后台启动，PID: $(cat "$DEPLOY_DIR/matching.pid")"
    fi
}

# ===================== 停止服务 =====================
stop_service() {
    log_info "停止服务..."
    if command -v systemctl &>/dev/null && systemctl is-enabled --quiet matching 2>/dev/null; then
        sudo systemctl stop matching
        log_info "systemd服务已停止"
    else
        # 传统方式停止
        if [ -f "$DEPLOY_DIR/matching.pid" ]; then
            local pid
            pid=$(cat "$DEPLOY_DIR/matching.pid")
            if kill -0 "$pid" 2>/dev/null; then
                kill -TERM "$pid"
                sleep 5
                if kill -0 "$pid" 2>/dev/null; then
                    kill -KILL "$pid"
                    log_warn "强制终止进程: $pid"
                fi
            fi
            rm -f "$DEPLOY_DIR/matching.pid"
        fi

        # 查找并终止可能的残留进程
        local pids
        pids=$(pgrep -f "matching.jar")
        if [ -n "$pids" ]; then
            echo "$pids" | xargs kill -TERM
            log_info "终止残留进程: $pids"
        fi
    fi
}

# ===================== 检查服务状态 =====================
check_service_status() {
    log_info "检查服务状态..."
    if command -v systemctl &>/dev/null && systemctl is-enabled --quiet matching 2>/dev/null; then
        sudo systemctl status matching
    else
        if [ -f "$DEPLOY_DIR/matching.pid" ]; then
            local pid
            pid=$(cat "$DEPLOY_DIR/matching.pid")
            if kill -0 "$pid" 2>/dev/null; then
                echo -e "${GREEN}服务运行中${NC} (PID: $pid)"
            else
                echo -e "${RED}服务未运行${NC} (PID文件存在但进程不存在)"
            fi
        else
            echo -e "${RED}服务未运行${NC} (无PID文件)"
        fi
    fi

    # 检查端口监听
    local port=8080
    if netstat -ln 2>/dev/null | grep -q ":$port "; then
        echo -e "${GREEN}端口 $port 正在监听${NC}"
    else
        echo -e "${RED}端口 $port 未监听${NC}"
    fi
}

# ===================== 健康检查 =====================
health_check() {
    log_info "执行健康检查..."
    local max_attempts=30
    local attempt=1
    while [ $attempt -le $max_attempts ]; do
        if curl -s "http://localhost:8080/actuator/health" | grep -q '"status":"UP"'; then
            log_info "健康检查通过 (尝试 $attempt/$max_attempts)"
            return 0
        fi
        echo -n "."
        sleep 2
        attempt=$((attempt + 1))
    done
    echo
    log_error "健康检查失败"
    return 1
}

# ===================== 查看日志 =====================
show_logs() {
    local lines=${1:-50}
    log_info "查看最近 $lines 行日志..."
    if command -v journalctl &>/dev/null && systemctl is-enabled --quiet matching 2>/dev/null; then
        sudo journalctl -u matching -n "$lines" -f
    else
        if [ -f "$DEPLOY_DIR/logs/matching.log" ]; then
            tail -n "$lines" -f "$DEPLOY_DIR/logs/matching.log"
        else
            log_error "日志文件不存在"
        fi
    fi
}

# ===================== 回滚到上一版本 =====================
rollback() {
    log_info "回滚到上一版本..."

    # 查找备份文件
    local backup_file
    backup_file=$(ls -t "$DEPLOY_DIR/bin"/*.bak 2>/dev/null | head -n1)
    if [ -z "$backup_file" ]; then
        log_error "未找到备份文件"
        exit 1
    fi
    log_info "找到备份文件: $(basename "$backup_file")"

    # 停止服务
    stop_service

    # 备份当前版本
    mv "$DEPLOY_DIR/bin/$JAR_NAME" "$DEPLOY_DIR/bin/$JAR_NAME.rollback.$(date +%Y%m%d_%H%M%S)"

    # 恢复备份版本
    mv "$backup_file" "$DEPLOY_DIR/bin/$JAR_NAME"

    # 启动服务
    start_service

    # 健康检查
    if health_check; then
        log_info "回滚成功"
    else
        log_error "回滚后健康检查失败"
        exit 1
    fi
}

# ===================== 完整部署流程 =====================
full_deploy() {
    local env=$1
    log_info "开始完整部署流程 (环境: $env)..."

    check_dependencies
    build_project
    create_deploy_dirs
    deploy_application "$env"
    create_systemd_service "$env"
    start_service

    if health_check; then
        log_info "部署成功完成!"
        check_service_status
    else
        log_error "部署失败"
        exit 1
    fi
}

# ===================== 显示帮助 =====================
show_help() {
    cat << EOF
撮合系统部署脚本

用法: $0 [COMMAND] [ENVIRONMENT] [OPTIONS]

环境:
  dev       开发环境
  test      测试环境
  prod      生产环境

命令:
  deploy [ENV]   完整部署流程
  build          仅构建项目
  start          启动服务
  stop           停止服务
  restart        重启服务
  status         查看服务状态
  health         健康检查
  logs [LINES]   查看日志 (默认50行)
  rollback       回滚到上一版本

选项:
  -h, --help      显示帮助信息
  --skip-build    跳过构建步骤
  --skip-tests    跳过测试

示例:
  $0 deploy prod      # 部署到生产环境
  $0 build            # 仅构建
  $0 restart          # 重启服务
  $0 logs 100         # 查看最近100行日志
  $0 rollback         # 回滚

目录结构:
  $DEPLOY_DIR/
  ├── bin/         # JAR文件
  ├── config/      # 配置文件
  ├── logs/        # 日志文件
  ├── data/        # 数据文件
  └── scripts/     # 脚本文件
EOF
}

# ===================== 主函数 =====================
main() {
    if [ $# -eq 0 ]; then
        show_help
        exit 0
    fi

    local cmd="$1"
    case "$cmd" in
        "deploy")
            local env=$2
            if [ -z "$env" ] || [ -z "${ENV_CONFIGS[$env]}" ]; then
                log_error "不支持的环境: $env"
                show_help
                exit 1
            fi
            full_deploy "$env"
            ;;
        "build")
            check_dependencies
            build_project
            ;;
        "start")
            start_service
            ;;
        "stop")
            stop_service
            ;;
        "restart")
            stop_service
            sleep 2
            start_service
            ;;
        "status")
            check_service_status
            ;;
        "health")
            health_check
            ;;
        "logs")
            show_logs "$2"
            ;;
        "rollback")
            rollback
            ;;
        "-h"|"--help")
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
SKIP_BUILD=false
SKIP_TESTS=false
while [[ $# -gt 0 ]]; do
    case $1 in
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --skip-tests)
            SKIP_TESTS=true
            shift
            ;;
        *)
            break
            ;;
    esac
done

# 执行主函数
main "$@"