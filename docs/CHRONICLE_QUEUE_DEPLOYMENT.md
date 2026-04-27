# Chronicle Queue 部署指南（本地存储 + Kafka 复制）

## 架构概述

```
主实例:
  撮合 → 写本地SSD(EventLog) → Kafka发结果(给下游) + Kafka发EventLog(给从实例)
                ↓
           定期写Snapshot(Redis)

从实例:
  消费Kafka(eventlog-sync) → 写本地SSD(EventLog)

主从切换:
  新主实例 → Redis读Snapshot → 回放本地EventLog增量 → 恢复完成(10-30ms)
```

## 1. 环境准备

### 本地存储
```bash
# 创建本地 EventLog 目录（建议 NVMe SSD）
sudo mkdir -p /data/matching/eventlog-queue
sudo chown -R matching:matching /data/matching/
sudo chmod -R 755 /data/matching/

# 检查磁盘空间（建议至少 100GB）
df -h /data/
```

### Kafka Topic 创建
```bash
# 创建 EventLog 复制 topic
kafka-topics.sh --create \
  --topic MATCHING_EVENTLOG_SYNC \
  --partitions 1 \
  --replication-factor 3 \
  --config min.insync.replicas=2 \
  --config retention.ms=604800000 \
  --bootstrap-server kafka1:9092

# 验证
kafka-topics.sh --describe --topic MATCHING_EVENTLOG_SYNC --bootstrap-server kafka1:9092
```

### Redis 确认
```bash
# Snapshot 数据存储在 Redis，确认 Redis 可用
redis-cli -c -h redis-node1 -p 7000 ping
```

## 2. 配置更新

```properties
# application.properties 关键配置
matching.eventlog.type=chronicle-queue
matching.eventlog.dir=/data/matching/eventlog-queue
matching.eventlog.roll-cycle=HOURLY
matching.eventlog.transaction.enabled=true

# EventLog 复制
matching.kafka.topic.eventlog-sync=MATCHING_EVENTLOG_SYNC

# 实例配置
matching.ha.instance-id=node-1  # node-2 for second instance
matching.ha.role=STANDBY        # 初始都是STANDBY
```

## 3. 部署流程

### 步骤1: 部署两个实例（都是STANDBY）
```bash
# 实例A
java -jar -Dmatching.ha.instance-id=node-1 matching.jar

# 实例B
java -jar -Dmatching.ha.instance-id=node-2 matching.jar
```

### 步骤2: 激活实例A为主实例
```bash
curl -X POST http://instance-a:8080/ops/ha/activate
```

### 步骤3: 验证状态
```bash
curl http://instance-a:8080/ops/ha/status  # PRIMARY/ACTIVE
curl http://instance-b:8080/ops/ha/status  # STANDBY/INACTIVE
```

## 4. 主从切换操作

### 计划内切换（升级实例A）
```bash
# 1. 将实例A切换为从实例
curl -X POST http://instance-a:8080/ops/ha/deactivate

# 2. 激活实例B为主实例（自动从 Redis Snapshot + 本地 EventLog 恢复）
curl -X POST http://instance-b:8080/ops/ha/activate

# 3. 升级实例A
systemctl stop matching-a
# 部署新版本
systemctl start matching-a

# 4. 切换回实例A为主实例（可选）
curl -X POST http://instance-b:8080/ops/ha/deactivate
curl -X POST http://instance-a:8080/ops/ha/activate
```

### 恢复流程
```
activate 触发:
  1. Redis 读 Snapshot → 恢复 OrderBook 到 seq=N     (~5-20ms)
  2. 本地 EventLog 回放 seq=N+1 到最新               (~1-10ms)
  3. 预热幂等缓存                                    (~1ms)
  4. 启动 Kafka 消费
  总恢复时间: 10-30ms
```

## 5. 监控指标

### Prometheus 指标
```
chronicle_queue_size                # 队列大小
chronicle_queue_current_seq         # 当前序列号
chronicle_queue_is_primary          # 主从状态
```

### 健康检查
```bash
curl http://instance:8080/actuator/health
curl http://instance:8080/ops/ha/status
```

### Redis Snapshot 监控
```bash
# 检查 Snapshot 是否正常写入
redis-cli -c GET matching:snapshot:BTCUSDT | wc -c  # 应有数据
redis-cli -c GET matching:eventlog:sent:BTCUSDT      # lastSentSeq
```

## 6. 故障处理

### 主实例 OOM 恢复
```bash
# 1. 重启故障实例
systemctl restart matching-a

# 2. 激活为主实例（自动从 Redis Snapshot + 本地 EventLog 恢复）
curl -X POST http://instance-a:8080/ops/ha/activate
```

### 本地磁盘故障
```bash
# 1. 停止实例
systemctl stop matching-a

# 2. 更换磁盘，创建目录
sudo mkdir -p /data/matching/eventlog-queue
sudo chown -R matching:matching /data/matching/

# 3. 重启实例（从 Redis Snapshot 恢复，无需本地 EventLog）
systemctl start matching-a
curl -X POST http://instance-a:8080/ops/ha/activate
```

## 7. 性能调优

### JVM 参数
```bash
-Xms32g -Xmx32g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=100
-XX:+UnlockExperimentalVMOptions
-XX:+UseTransparentHugePages
```

### Chronicle Queue 参数
```properties
matching.eventlog.block-size=128MB
matching.eventlog.roll-cycle=DAILY
matching.eventlog.checkpoint.interval-ms=1000
```

## 8. 容量规划

### 存储容量
```
每小时数据量 ≈ TPS × 3600 × 平均事件大小
例: 100万TPS × 3600 × 1KB = 3.6GB/小时

建议配置: 500GB NVMe SSD（支持约 5 天数据）
```

### Redis 内存
```
Snapshot 大小: 每交易对 ~1MB（1万挂单）
10 交易对: ~10MB
Redis 内存增量: 可忽略
```

### 内存需求
```
Chronicle Queue 内存映射: 约 1-2GB
JVM 堆内存: 建议 32GB+
系统预留: 8GB+
总计: 48GB+ 内存
```

## 注意事项

1. **本地 SSD 性能**: 建议使用 NVMe SSD，避免 SATA SSD
2. **Kafka eventlog-sync**: `min.insync.replicas=2`，确保复制消息不丢
3. **Redis Snapshot**: 定期检查 Snapshot 写入是否正常
4. **磁盘空间**: 定期清理旧的 Chronicle Queue 文件
5. **监控告警**: 设置队列大小、延迟、磁盘空间告警
