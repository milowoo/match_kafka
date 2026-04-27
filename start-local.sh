#!/bin/bash

# 本地开发环境启动脚本 - Redis单机模式

echo "=== 撮合服务本地开发环境启动 ==="
echo "环境: 本地开发"
echo "Redis: 单机模式 (localhost:6379)"
echo "Chronicle Queue: 启用"
echo "端口: 8081"
echo

# JDK 21 Chronicle Queue 兼容参数
JVM_ARGS="--add-opens java.base/java.lang.reflect=ALL-UNNAMED \
--add-opens java.base/java.nio=ALL-UNNAMED \
--add-opens java.base/sun.nio.ch=ALL-UNNAMED \
--add-opens java.base/java.lang=ALL-UNNAMED \
--add-opens java.base/java.util=ALL-UNNAMED \
--add-opens java.base/java.io=ALL-UNNAMED \
--add-opens java.base/java.lang.invoke=ALL-UNNAMED \
--add-opens java.base/sun.misc=ALL-UNNAMED \
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED"

# 应用配置参数
APP_ARGS="--spring.config.location=file:application-local.properties \
--spring.profiles.active=local"

# 检查Redis是否启动
echo "检查Redis服务状态..."
if ! redis-cli ping > /dev/null 2>&1; then
    echo "⚠️  Redis服务未启动, 正在启动..."
    brew services start redis
    sleep 2
    if redis-cli ping > /dev/null 2>&1; then
        echo "✅ Redis服务启动成功"
    else
        echo "❌ Redis服务启动失败, 请手动启动"
        exit 1
    fi
else
    echo "✅ Redis服务已运行"
fi

echo
echo "启动参数:"
echo "JVM: $JVM_ARGS"
echo "APP: $APP_ARGS"
echo

java $JVM_ARGS -jar $APP_ARGS target/matching.jar