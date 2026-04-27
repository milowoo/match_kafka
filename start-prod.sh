#!/bin/bash

# 生产环境启动脚本 - Redis集群模式

echo "=== 撮合服务生产环境启动 ==="
echo "环境: 生产环境"
echo "Redis: 集群模式"
echo "Chronicle Queue: 启用"
echo "端口: 8080"
echo

# 检查必要的环境变量
required_vars=("REDIS_CLUSTER_NODES" "REDIS_PASSWORD" "KAFKA_BOOTSTRAP_SERVERS" "INSTANCE_ID")
for var in "${required_vars[@]}"; do
    if [ -z "${!var}" ]; then
        echo "❌ 环境变量 $var 未设置"
        exit 1
    fi
done

echo "✅ 环境变量检查通过"
echo "Redis集群节点: $REDIS_CLUSTER_NODES"
echo "Kafka集群: $KAFKA_BOOTSTRAP_SERVERS"
echo "实例ID: $INSTANCE_ID"
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

# 生产环境JVM优化参数
JVM_OPTS="-Xms2g -Xmx4g \
-XX:+UseG1GC \
-XX:MaxGCPauseMillis=200 \
-XX:+UnlockExperimentalVMOptions \
-XX:+UseStringDeduplication \
-XX:+PrintGCDetails \
-XX:+PrintGCTimeStamps \
-Xloggc:/var/log/matching/gc.log"

# 应用配置参数
APP_ARGS="--spring.config.location=file:application-prod.properties \
--spring.profiles.active=prod"

echo "JVM参数: $JVM_ARGS $JVM_OPTS"
echo "应用参数: $APP_ARGS"
echo

# 创建日志目录
mkdir -p /var/log/matching

# 启动应用
java $JVM_ARGS $JVM_OPTS -jar $APP_ARGS target/matching.jar