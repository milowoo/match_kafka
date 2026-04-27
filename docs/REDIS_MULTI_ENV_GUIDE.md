# Redis多环境配置部署指南

## 🎯 概述

撮合服务现在支持Redis的**智能模式切换**：
- **本地开发**: 自动使用单机模式 (localhost:6379)
- **生产环境**: 自动使用集群模式 (多节点)

## 🔧 配置原理

### 智能检测逻辑
```java
// 如果配置了集群节点，自动使用集群模式
if (clusterNodes != null && !clusterNodes.isEmpty() && !clusterNodes.get(0).trim().isEmpty()) {
    // 集群模式
} else {
    // 单机模式
}
```

### 配置参数对比

| 配置项 | 本地开发 | 生产环境 |
|--------|----------|----------|
| `spring.data.redis.host` | localhost | - |
| `spring.data.redis.port` | 6379 | - |
| `spring.data.redis.cluster.nodes` | - | redis-node1:7000,redis-node2:7000,... |
| `spring.data.redis.password` | 空 | 强密码 |
| `jedis.pool.max-active` | 8 | 200 |

## 🚀 部署方式

### 1. 本地开发环境

```bash
# 启动Redis单机
brew services start redis

# 启动撮合服务
./start-local.sh
```

**配置文件**: `application-local.properties`
```properties
# Redis单机模式
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.password=
```

### 2. 生产环境

```bash
# 设置环境变量
export REDIS_CLUSTER_NODES="redis-node1:7000,redis-node2:7000,redis-node3:7000"
export REDIS_PASSWORD="your-strong-password"
export KAFKA_BOOTSTRAP_SERVERS="kafka1:9092,kafka2:9092,kafka3:9092"
export INSTANCE_ID="node-1"

# 启动撮合服务
./start-prod.sh
```

**配置文件**: `application-prod.properties`
```properties
# Redis集群模式
spring.data.redis.cluster.nodes=${REDIS_CLUSTER_NODES}
spring.data.redis.password=${REDIS_PASSWORD}
spring.data.redis.cluster.max-redirects=3
```

## 📊 监控和验证

### Redis连接信息API
```bash
# 查看连接信息
curl http://localhost:8081/ops/redis/info
# 返回:
{
  "mode": "STANDALONE",  # 或 "CLUSTER"
  "connection": "Standalone: localhost:6379",
  "status": "CONNECTED",
  "ping": "PONG",
  "dbSize": 1
}

# 测试读写功能
curl http://localhost:8081/ops/redis/test
# 返回:
{
  "status": "SUCCESS",
  "mode": "STANDALONE",
  "writeRead": true,
  "duration": "3ms"
}
```

### 启动日志验证
```
[Redis] Standalone mode detected - localhost:6379, profile: local
[Redis] Standalone pool config - maxTotal: 8, maxIdle: 8, minIdle: 2
[Redis] Creating standalone connection factory for localhost:6379
[Redis] StringRedisTemplate configured with STANDALONE mode
```

## 🔍 故障排查

### 1. 连接失败
```bash
# 检查Redis服务状态
redis-cli ping

# 检查集群状态（生产环境）
redis-cli -c -h redis-node1 -p 7000 cluster nodes
```

### 2. 配置验证
```bash
# 查看实际使用的配置
curl http://localhost:8081/ops/redis/info

# 查看健康检查
curl http://localhost:8081/actuator/health | jq '.components.redis'
```

### 3. 性能监控
```bash
# 查看连接池状态
curl http://localhost:8081/actuator/metrics/redis.connections.active

# 查看Redis统计
curl http://localhost:8081/ops/redis/stats
```

## ⚙️ 高级配置

### 集群模式优化
```properties
# 生产环境集群优化
spring.data.redis.jedis.pool.max-active=200  # 集群需要更多连接
spring.data.redis.cluster.max-redirects=3    # 重定向次数
spring.data.redis.timeout=2000ms             # 超时时间
```

### 连接池调优
```properties
# 连接池参数
spring.data.redis.jedis.pool.max-idle=100    # 最大空闲连接
spring.data.redis.jedis.pool.min-idle=20     # 最小空闲连接
spring.data.redis.jedis.pool.test-on-borrow=true   # 借用时测试
spring.data.redis.jedis.pool.test-while-idle=true  # 空闲时测试
```

## 🎉 优势总结

### ✅ 智能切换
- 无需修改代码，自动检测环境
- 配置驱动，灵活适配

### ✅ 性能优化
- 单机模式：轻量级配置，适合开发
- 集群模式：高可用配置，适合生产

### ✅ 监控完善
- 连接状态实时监控
- 性能指标详细记录
- 故障诊断信息丰富

### ✅ 运维友好
- 启动脚本自动化
- 环境变量标准化
- 日志信息详细清晰

## 📋 部署检查清单

### 本地开发
- [ ] Redis服务已启动 (`redis-cli ping`)
- [ ] 使用 `application-local.properties`
- [ ] 端口8081可用
- [ ] 日志显示 "STANDALONE mode"

### 生产环境
- [ ] Redis集群正常运行
- [ ] 环境变量已设置 (REDIS_CLUSTER_NODES, REDIS_PASSWORD等)
- [ ] 使用 `application-prod.properties`
- [ ] 共享存储已挂载 (/mnt/shared/matching/)
- [ ] 日志显示 "CLUSTER mode"
- [ ] 监控告警已配置

这样的配置确保了开发和生产环境的无缝切换，提高了部署的灵活性和可靠性！