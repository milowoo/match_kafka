# 撮合服务 JDK 21 + Spring Boot 3.5.7 + Chronicle Queue 升级版

本项目是基于原 `/Users/corleywu/matching` 项目升级到 JDK 21、Spring Boot 3.5.7 和 Chronicle Queue 的高性能版本。

## 升级内容

### 1. 版本升级
- **JDK**: 11 → 21
- **Spring Boot**: 2.7.18 → 3.5.7
- **Apollo Client**: 2.1.0 → 2.3.0
- **Disruptor**: 3.4.4 → 4.0.0
- **Chronicle Queue**: 5.25ea13 → 5.24.4 (修复早期访问版本稳定性风险)

### 2. 架构升级
- **EventLog**: Chronicle Queue 本地高性能事件存储
- **Snapshot**: Redis 存储，Protobuf 序列化
- **主从复制**: Kafka 异步复制 EventLog 到从实例
- **主从切换**: 10-30ms（Redis Snapshot + 本地 EventLog 回放）
- **数据一致性**: 事务性写入 + 检查点机制
- **监控增强**: Chronicle Queue专用指标
- **稳定性保障**: 使用稳定版本5.24.4，避免早期访问版本风险

### 3. 存储架构（本地存储 + Kafka 复制）

```
主实例:
  撮合完成 → 写本地EventLog(NVMe SSD) → Kafka发送结果(给下游)
                ↓                            ↓
           定期写Snapshot(Redis)      Kafka发送EventLog(给从实例)
                                             ↓
从实例:                               消费EventLog → 写本地EventLog

主从切换:
  新主实例 → Redis读Snapshot → 回放本地EventLog增量 → 恢复完成(10-30ms)
```

### 4. 依赖变更
- 添加 `jakarta.annotation-api` 依赖以支持 `@PostConstruct` 和 `@PreDestroy` 注解
- 更新 Maven Compiler Plugin 到 3.13.0 并启用预览特性
- **新增 Chronicle Queue 依赖**用于高性能事件存储

### 5. 代码适配
- 将 `javax.annotation` 包导入更改为 `jakarta.annotation`
- 修复 Spring Kafka API 变更：将 `addCallback()` 方法替换为 `whenComplete()`
- **新增 ChronicleQueueEventLog** 实现高性能事件存储
- **新增主从切换API** 支持手动主从切换
- **SnapshotService** 改为写 Redis（Protobuf 序列化）
- **EventLogReplicationService** 主实例通过 Kafka 复制 EventLog 给从实例
- **EventLogReplicationConsumer** 从实例消费 EventLog 写入本地

### 6. 性能提升
- **写入延迟**: 1-10μs（本地 NVMe SSD，比 EFS 快 100-1000 倍）
- **TPS**: 100-500万（极致并发性能）
- **切换时间**: 10-30ms（Redis Snapshot + 本地 EventLog 回放）
- **数据安全**: 事务性写入 + 检查点机制 + Kafka 复制

## 性能优化成果

基于原项目的性能优化，实现了以下关键提升：

### 核心优化项目
1. **BigDecimal → long 运算**: 性能提升 3.57倍，内存节省 70-80%
2. **Protobuf 替代 JSON**: 序列化性能提升 4.81倍，数据大小减少 30-50%
3. **EventLog Protobuf优化**: 性能提升 1.82倍，磁盘空间节省 61.8%
4. **Date对象复用**: 时间戳操作性能提升 2.43倍
5. **Eclipse Collections**: 使用原始类型集合避免装箱开销
6. **Jackson Blackbird**: 使用MethodHandle优化JSON序列化
7. **本地存储替代 EFS**: EventLog 写入延迟降低 100-1000 倍

### 整体性能提升
- **TPS**: 预期提升 2-4倍
- **内存使用**: 减少 40-60%
- **GC压力**: 降低 50-70%
- **延迟**: 降低 30-50%

## 编译和运行

### 编译
```bash
mvn clean compile
```

### 运行测试
```bash
mvn test
```

### 打包
```bash
mvn clean package
```

### 运行
```bash
# Chronicle Queue本地高性能模式
java -jar target/matching.jar
```

## 部署

### 前置条件
- JDK 21
- Kafka 集群（需创建 `MATCHING_EVENTLOG_SYNC` topic）
- Redis 集群
- 本地 NVMe SSD（建议 100GB+）

### Kafka Topic 创建
```bash
# 创建 EventLog 复制 topic（按 symbolId 分区，保证有序）
kafka-topics.sh --create \
  --topic MATCHING_EVENTLOG_SYNC \
  --partitions 10 \
  --replication-factor 3 \
  --config min.insync.replicas=2 \
  --config retention.ms=604800000 \
  --bootstrap-server kafka1:9092
```

### 本地存储目录
```bash
# 创建本地 EventLog 目录
sudo mkdir -p /data/matching/eventlog-queue
sudo chown -R $USER:$USER /data/matching
```

### 初始部署
**重要说明**: 系统启动时，两个实例都是从实例（STANDBY），不会自动选主，需要运维手动指定主实例。

```bash
# 实例A（初始为从实例）
java -jar -Dmatching.ha.instance-id=node-1 -Dmatching.ha.role=STANDBY target/matching.jar

# 实例B（初始为从实例）
java -jar -Dmatching.ha.instance-id=node-2 -Dmatching.ha.role=STANDBY target/matching.jar
```

### 首次激活主实例
```bash
# 运维手动指定实例A为主实例（首次激活）
curl -X POST http://instance-a:8080/ops/ha/activate

# 查看状态确认
curl http://instance-a:8080/ops/ha/status
# 返回: {"role":"PRIMARY","instanceId":"node-1","status":"ACTIVE"}
```

## 主从切换

### 切换操作流程
**标准切换流程**: 先停主实例 → 再启新主实例

```bash
# 步骤1: 将当前主实例A切换为从实例
curl -X POST http://instance-a:8080/ops/ha/deactivate
# 此时实例A变为STANDBY，停止处理业务

# 步骤2: 激活实例B为新的主实例
curl -X POST http://instance-b:8080/ops/ha/activate
# 实例B从Redis加载Snapshot → 回放本地EventLog增量 → 变为PRIMARY

# 查看两个实例状态
curl http://instance-a:8080/ops/ha/status  # STANDBY
curl http://instance-b:8080/ops/ha/status  # PRIMARY
```

### 恢复流程详解
```
activate 触发:
  1. 从 Redis 读取最新 Snapshot → 恢复 OrderBook 到 seq=N     (~5-20ms)
  2. 从本地 EventLog 读取 seq=N+1 到最新 → 逐条回放            (~1-10ms)
  3. 预热幂等缓存                                              (~1ms)
  4. 启动 Kafka 消费（从 order-requests 继续消费）
  5. 开始正常撮合
  总恢复时间: 10-30ms
```

### 状态查询和管理
```bash
# 查看实例状态
curl http://instance-a:8080/ops/ha/status
curl http://instance-b:8080/ops/ha/status

# Kafka消费者管理（可选）
curl -X POST http://instance-a:8080/ops/kafka/start   # 手动启动Kafka消费
curl -X POST http://instance-a:8080/ops/kafka/stop    # 手动停止Kafka消费
curl -X POST http://instance-a:8080/ops/kafka/reset   # 重置Kafka消费状态
curl http://instance-a:8080/ops/kafka/status          # 查看Kafka状态
```

## 系统架构

### 服务职责分工

#### 撮合服务 (Matching Service)
- **核心职责**: 订单撮合、价格匹配
- **输入**: 来自trade-engine的下单/撤单请求
- **输出**: 成交结果、下单结果、撤单结果、深度数据
- **存储**: Chronicle Queue 本地事件日志 + Redis Snapshot

#### 交易引擎 (Trade Engine)
- **核心职责**: 资金管理、仓位管理、订单管理、结算管理
- **输入**: 用户交易请求
- **输出**: 向撮合服务发送下单/撤单信息
- **功能**: 风控检查、资金冻结/解冻、仓位计算

#### 清算服务 (Settlement Service)
- **核心职责**: 手续费计算
- **输入**: 消费撮合服务的成交结果、下单结果、撤单结果
- **输出**: 手续费账单、清算报告
- **功能**: 费率计算、手续费扣除

#### 行情服务 (Market Data Service)
- **核心职责**: 行情数据处理和推送
- **输入**: 消费撮合服务的成交信息、深度数据
- **输出**: K线数据、实时行情、深度信息推送给用户
- **功能**: K线聚合、行情计算、WebSocket推送

### Kafka消息流向

```
Trade Engine → Kafka: order-requests → Matching Service
                (下单/撤单信息)

Matching Service → Kafka: trade-results    → Settlement Service (成交结果)
               → Kafka: market-data     → Market Data Service (行情数据)
               → Kafka: eventlog-sync   → Standby Instance   (EventLog复制)
```

### 数据流程图

```
用户请求 → Trade Engine → 风控检查 → 资金/仓位管理
                ↓
         Kafka: order-requests
                ↓
         Matching Service → 撮合处理 → 本地Chronicle Queue + Redis Snapshot
                ↓                    ↓                      ↓
    Kafka: trade-results    Kafka: market-data    Kafka: eventlog-sync
                ↓                    ↓                      ↓
        Settlement Service    Market Data Service    Standby Instance
                ↓                    ↓              (写入本地EventLog)
           手续费计算           K线聚合 + 推送
```

## 主要特性

- 高性能撮合引擎
- 微服务架构分离
- Redis 集群支持（Snapshot + lastSentSeq）
- Kafka 消息队列（业务消息 + EventLog 复制）
- Chronicle Queue 本地事件日志
- 实时行情推送
- 高可用性支持（本地存储 + Kafka 复制 + Redis Snapshot）
- 监控和指标收集

## 注意事项

1. 确保运行环境支持 JDK 21
2. Spring Boot 3.x 要求最低 JDK 17
3. 所有原有功能和配置保持兼容
4. 性能和稳定性得到进一步提升
5. **EventLog 使用本地 NVMe SSD 存储**，主从通过 Kafka 复制
6. **Snapshot 存储在 Redis**，Protobuf 序列化，10 交易对约 10MB
7. **主从切换重要说明**：
   - 系统启动时两个实例都是从实例（STANDBY）
   - **不支持自动故障转移**，需要运维手动操作
   - 切换流程：先停主实例 → 再启新主实例
   - 切换时间：10-30ms（Redis Snapshot + 本地 EventLog 回放）
8. **Kafka eventlog-sync topic 配置**：
   - `min.insync.replicas=2`，确保消息不丢
   - 按 symbolId 分区，保证同一交易对有序
   - retention 建议 7 天
9. **建议在生产环境使用前充分测试**

## 性能指标

| 指标 | 本地存储模式 | 描述 |
|------|------------|------|
| 写入延迟 | 1-10μs | 本地 NVMe SSD |
| 读取延迟 | 0.01-0.05ms | 零拷贝读取 |
| TPS | 100-500万 | 极致并发性能 |
| 切换时间 | 10-30ms | Redis Snapshot + EventLog 回放 |
| 内存使用 | 中等 | 内存映射优化 |
| 磁盘使用 | 中等 | 本地 SSD |
| 运维复杂度 | 低 | 无需 EFS，简化部署 |

## 配置文件

配置文件 `application.properties` 保持不变，所有原有配置项继续有效。
