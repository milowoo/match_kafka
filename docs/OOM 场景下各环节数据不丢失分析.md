# OOM 场景下各环节数据不丢失分析

## 架构说明

本分析基于当前架构：本地 NVMe SSD 存储 EventLog + Kafka 复制给从实例 + Redis 存储 Snapshot。

## 核心保障机制

| 机制 | 说明 |
|------|------|
| Kafka MANUAL ack | EventLog 写入后才提交 offset，未提交则重启重消费 |
| EventLog 本地持久化 | Chronicle Queue 写入本地 NVMe SSD，进程崩溃数据不丢 |
| Kafka EventLog 复制 | Phase 3 实时调用 `replicateEvent`，主实例写入本地后复制给从实例 |
| Redis Snapshot | 定期（60秒）将 OrderBook 全量快照写入 Redis |
| lastSentSeq | 存储在 Redis（key: `matching:eventlog:sent:{symbolId}`），记录已发送给下游的最大 seq |
| 幂等检查（双层） | L1: 本地 LRU 缓存（orderIndex + idempotentService.markLocal）；L2: Redis key |
| seq 幂等去重 | 从实例 `appendReplicated` 里 `eventSeq <= committedSeq` 时直接跳过，防止重复写入 |

## 数据流与 ack 时序

```
Kafka消费 → Disruptor入队 → 撮合处理
                                ↓
                    Phase 1: 本地EventLog写入(持久化) ✓
                                ↓
                    Phase 2: Kafka ack(提交offset) ✓
                                ↓
                    Phase 3: asyncIoExecutor(best-effort)
                        ├── replicateEvent → Kafka eventlog-sync → 从实例
                        ├── Redis 幂等标记
                        ├── sendBatch → 下游 Kafka
                        │     └── onComplete: writeLastSentSeq(Redis)
                        └── Depth 推送
```

关键设计：**ack 在本地 EventLog 写入之后、结果发送之前**。

## 7 个 OOM 场景分析

### 场景 1：Kafka 收到消息后、Disruptor 入队前 OOM

```
Kafka消费 → [OOM] → Disruptor入队
```

| 项目 | 说明 |
|------|------|
| offset 状态 | 未提交（MANUAL ack 尚未调用） |
| 恢复方式 | 重启后 Kafka 从上次 committed offset 重新消费 |
| 数据丢失 | ❌ 不丢失 |
| 结果 | ✅ 安全 |

---

### 场景 2：Disruptor 处理中 OOM

```
Disruptor入队 → 撮合处理 → [OOM]
```

| 项目 | 说明 |
|------|------|
| offset 状态 | 未提交（ack 在 Phase 2 才调用） |
| 恢复方式 | 重启后 Kafka 重新消费，重新撮合 |
| 数据丢失 | ❌ 不丢失 |
| 结果 | ✅ 安全 |

---

### 场景 3：本地 EventLog 写入前 OOM

```
撮合处理 → [OOM] → Phase 1: 本地EventLog写入
```

| 项目 | 说明 |
|------|------|
| offset 状态 | 未提交 |
| EventLog 状态 | 未写入 |
| 恢复方式 | 重启后 Kafka 重新消费 → 重新撮合 → 重新写入 EventLog |
| 数据丢失 | ❌ 不丢失 |
| 结果 | ✅ 安全 |

---

### 场景 4：本地 EventLog 写入后、ack 前 OOM（已修复）

```
Phase 1: 本地EventLog写入(seq=N) ✓ → [OOM] → Phase 2: Kafka ack
```

| 项目 | 说明 |
|------|------|
| offset 状态 | 未提交 |
| 本地 EventLog 状态 | 已持久化到本地 SSD（seq=N） |
| Phase 3 状态 | 未执行（replicateEvent 未发出，从实例可能缺失 seq=N） |
| lastSentSeq | 未更新（Redis 里仍是旧值 M） |

**重启恢复流程**：

```
1. Redis Snapshot(seq=M) → 恢复 OrderBook 到 seq=M
2. 读本地 EventLog seq=M+1 到 seq=N → 增量回放
3. OrderBook 恢复到 seq=N（与 OOM 前完全一致）
4. 幂等缓存预热（修复A）：
   - 3a. OrderBook 里还在挂单的订单 → markLocal
   - 3b. lastSentSeq 到 seq=N 窗口内已成交/撤单的订单 → markLocal
        （这些订单已不在 orderIndex 里，必须靠幂等缓存拦截重复撮合）
5. 补发增量 EventLog 给从实例（修复B）：
   - 补发范围 = seq=M+1 到 seq=N（Snapshot 之后的增量，通常 60 秒内数据量极小）
   - 从实例 appendReplicated 里 seq 幂等去重，已有的 seq 直接跳过
6. 启动 Kafka 消费，重消费 offset 未提交的订单
7. 幂等检查命中（orderIndex 或 idempotentService）→ cmdResult=null → 直接 ack，不重新撮合
8. 继续正常处理后续消息
```

| 数据丢失 | ❌ 不丢失 |
|---------|---------|
| 重复风险 | 无（幂等缓存预热覆盖了已成交订单） |
| 从实例一致性 | ✅ 补发增量 EventLog 确保从实例不漏数据 |
| 结果 | ✅ 安全 |

**修复前的漏洞**：
- 已成交订单从 `orderIndex` 移除，`orderIndex` 检查不命中
- Redis 幂等标记在 Phase 3 写入，OOM 时可能未写
- 导致重启后重新撮合，产生重复成交

**修复方案**：
- 修复A：`EventLogLoader.loadFromEventLog` 回放时，把 `lastSentSeq < seq <= N` 窗口内所有订单 orderId 写入本地幂等 LRU 缓存
- 修复B：回放完成后，把 `seq=M+1 到 seq=N` 的增量 EventLog 补发给从实例（精确范围，不会造成 Kafka 数据量暴增）

---

### 场景 5：ack 后、发送结果时 OOM

```
Phase 1: 本地EventLog写入 ✓ → Phase 2: Kafka ack ✓ → [OOM] → Phase 3: sendBatch
```

| 项目 | 说明 |
|------|------|
| offset 状态 | 已提交（不会重消费） |
| 本地 EventLog 状态 | 已持久化 |
| replicateEvent 状态 | 未执行（从实例可能缺失该事件） |
| lastSentSeq 状态 | 未更新（Redis 里仍是旧值，sendBatch onComplete 未执行） |

**重启恢复流程**：

```
1. EventLogLoader 读 Redis lastSentSeq → 旧值 M（< N）
2. 回放 EventLog，发现 seq > M 的事件有 matchResults
3. enqueueForRetry → ResultOutboxService 重发撮合结果给下游
4. sendBatch onComplete → writeLastSentSeq(N) 更新 Redis
5. 补发 seq=M+1 到 seq=N 的增量 EventLog 给从实例
```

| 数据丢失 | ❌ 不丢失 |
|---------|---------|
| 重复风险 | ⚠️ 有重复（结果可能重发），下游需幂等 |
| 结果 | ✅ 安全 |

---

### 场景 6：部分发送成功后 OOM

```
Phase 1 ✓ → Phase 2 ✓ → 发送结果A ✓ → 发送结果B ✓ → [OOM] → 发送结果C
```

| 项目 | 说明 |
|------|------|
| offset 状态 | 已提交 |
| 本地 EventLog 状态 | 已持久化 |
| lastSentSeq 状态 | 未更新（批量发送完成后才写入） |

**重启恢复**：`EventLogLoader` 从 `lastSentSeq` 恢复 → 重发 A、B、C（A、B 重复发送）

| 数据丢失 | ❌ 不丢失 |
|---------|---------|
| 重复风险 | ⚠️ 有重复，下游需幂等 |
| 结果 | ✅ 安全 |

---

### 场景 7：主实例宕机，从实例接管

```
主实例: 本地EventLog写入(seq=N) ✓ → Phase 3: replicateEvent → [宕机]
从实例: 本地EventLog可能只到 seq=N-1（Kafka 复制有延迟窗口）
```

| 项目 | 说明 |
|------|------|
| 从实例 EventLog | 可能缺少最后 1-2 条（Kafka 复制延迟窗口） |
| Redis Snapshot | 最近 60 秒内的全量快照（seq=M） |
| 缺失订单保障 | 主实例 ack 在 EventLog 写入后，offset 未提交 → 新主实例重消费 |

**从实例 activate 恢复流程**：

```
1. Redis Snapshot(seq=M) → 恢复 OrderBook
2. 本地 EventLog(seq=M+1 到 seq=K，K 可能 < N) → 增量回放
3. 幂等缓存预热（同场景4修复A）
4. 启动 Kafka 消费
5. 缺失的 seq=K+1 到 seq=N 对应订单：offset 未提交 → 重消费 → 重新撮合
6. 幂等检查：已在 OrderBook 里的订单跳过，缺失的正常撮合
```

| 数据丢失 | ❌ 不丢失 |
|---------|---------|
| 重复风险 | ⚠️ 可能重复（缺失订单重撮合），下游需幂等 |
| 结果 | ✅ 安全 |

---

## 风险总结

| 场景 | 数据丢失 | 重复风险 | 下游要求 | 备注 |
|------|---------|---------|---------|------|
| 场景 1：Kafka 消费后 OOM | ✅ 不丢失 | 无 | 无 | |
| 场景 2：Disruptor 中 OOM | ✅ 不丢失 | 无 | 无 | |
| 场景 3：EventLog 写入前 OOM | ✅ 不丢失 | 无 | 无 | |
| 场景 4：EventLog 写入后 OOM | ✅ 不丢失 | 无 | 无 | 已修复：幂等缓存预热 + 增量补发 |
| 场景 5：ack 后发送前 OOM | ✅ 不丢失 | ⚠️ 有重复 | 下游幂等 | |
| 场景 6：部分发送后 OOM | ✅ 不丢失 | ⚠️ 有重复 | 下游幂等 | |
| 场景 7：主实例宕机切换 | ✅ 不丢失 | ⚠️ 可能重复 | 下游幂等 | |

## 结论

- **数据零丢失**：所有 7 个场景均不会丢失数据
- **重复风险**：场景 5、6、7 可能重复发送，下游服务需实现幂等消费
- **场景4 已修复**：
  - 修复A：幂等缓存预热覆盖已成交订单，防止重复撮合
  - 修复B：精确补发 Snapshot 之后的增量 EventLog 给从实例（60秒窗口，数据量极小）
  - 从实例 `appendReplicated` 加入 seq 幂等去重，重复消息直接跳过
- **正常路径修复**：`MatchEventHandler` Phase 3 补充了 `replicateEvent` 调用（之前遗漏）
- **lastSentSeq 存储**：Redis key `matching:eventlog:sent:{symbolId}`，在 `sendBatch` 成功回调后更新
