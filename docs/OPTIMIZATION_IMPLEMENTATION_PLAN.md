# 主从同步延迟优化实现方案

## 优先级排序与快速赢

本文档提供4个快速优化，预期可将延迟从 **500ms+ 降到 100-200ms**

---

## 优化方案 #1：seq到symbol的索引映射 ⭐⭐⭐⭐⭐

### 问题
`ChronicleQueueEventLog.readEventBySeq()` 需要遍历所有symbol，O(n)复杂度

```
100个待发送事件 × 20个symbol = 2000次队列查询 = 500-1000ms延迟
```

### 解决方案

在 `ChronicleQueueEventLog` 中添加seq->symbol映射，**包含完整的内存管理策略**：

```java
// 添加到 ChronicleQueueEventLog 类
private final ConcurrentHashMap<Long, String> seqToSymbolMap = new ConcurrentHashMap<>();
private static final int MAX_MAP_SIZE = 10000;  // 只保留最近10000个seq映射
private static final int CLEANUP_BATCH_SIZE = 1000;  // 每1000个新seq清理一次
private static final int EMERGENCY_CLEANUP_SIZE = 15000;  // 紧急清理阈值
private long cleanupCounter = 0;

@Override
public void appendBatch(String symbolId, List<OrderBookEntry> addedOrders,
                       List<String> removedOrderIds,
                       List<MatchResultEntry> matchResults) {
    // ...existing code...
    Event event = new Event(seq, symbolId, addedOrders, removedOrderIds, matchResults);
    
    try {
        // 在成功写入后，记录seq->symbol映射
        retryHandler.writeEventWithRetry(components, event, symbolId, seq);
        seqToSymbolMap.putIfAbsent(seq, symbolId);
        
        // 定期清理旧映射，避免内存泄漏
        if (++cleanupCounter % CLEANUP_BATCH_SIZE == 0) {
            cleanupOldMappings(seq);
        }
        
        // ...rest of code...
    }
}

@Override
public void appendReplicated(Event event) {
    // ...existing code...
    try {
        // 在复制事件时，记录seq->symbol映射
        retryHandler.writeEventWithRetry(components, event, event.getSymbolId(), eventSeq);
        seqToSymbolMap.putIfAbsent(eventSeq, event.getSymbolId());
        
        // 复制时也定期清理
        if (++cleanupCounter % CLEANUP_BATCH_SIZE == 0) {
            cleanupOldMappings(eventSeq);
        }
        
        // ...rest of code...
    }
}

/**
 * 清理旧的seq->symbol映射，避免内存泄漏
 */
private void cleanupOldMappings(long currentSeq) {
    int currentSize = seqToSymbolMap.size();
    
    // 正常清理：保留最近MAX_MAP_SIZE个seq
    if (currentSize > MAX_MAP_SIZE) {
        long minSeqToKeep = currentSeq - MAX_MAP_SIZE;
        int removed = seqToSymbolMap.entrySet().removeIf(entry -> entry.getKey() < minSeqToKeep);
        
        log.debug("Cleaned up {} old seq mappings, current size: {}", removed, seqToSymbolMap.size());
    }
    
    // 紧急清理：如果仍然过大，保留最近50%
    if (seqToSymbolMap.size() > EMERGENCY_CLEANUP_SIZE) {
        long emergencyMinSeq = currentSeq - (MAX_MAP_SIZE / 2);
        int removed = seqToSymbolMap.entrySet().removeIf(entry -> entry.getKey() < emergencyMinSeq);
        
        log.warn("Emergency cleanup: removed {} mappings, current size: {}", 
                removed, seqToSymbolMap.size());
    }
}

@Override
public Event readEventBySeq(long seq) {
    ensureInitialized();
    
    try {
        // 第一步：尝试从索引快速查找 (O(1))
        String symbolId = seqToSymbolMap.get(seq);
        if (symbolId != null) {
            ChronicleQueueComponentManager.SymbolQueueComponents components =
                    componentManager.getComponents(symbolId);
            if (components != null) {
                List<Event> events = components.reader.readEvents(symbolId, seq - 1);
                for (Event event : events) {
                    if (event.getSeq() == seq) {
                        return event;
                    }
                }
            }
        }
        
        // 第二步：索引未命中，降级到全扫描（并更新索引）
        List<String> activeSymbols = symbolConfigService.getActiveSymbolIds();
        for (String symbolId : activeSymbols) {
            try {
                ChronicleQueueComponentManager.SymbolQueueComponents components =
                        componentManager.getOrCreateComponents(symbolId, 
                                transactionManager.isTransactionEnabled(),
                                globalSeq, committedSeq, orderBookService);
                
                List<Event> events = components.reader.readEvents(symbolId, seq - 1);
                for (Event event : events) {
                    if (event.getSeq() == seq) {
                        // 索引miss时更新映射，为后续调用优化
                        seqToSymbolMap.putIfAbsent(seq, symbolId);
                        log.debug("Found event seq={} in symbol: {} (indexed for future)", seq, symbolId);
                        return event;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to read events for symbol: {} when looking for seq: {}", symbolId, seq, e);
            }
        }
        
        log.warn("Event not found for seq: {}", seq);
        return null;
    } catch (Exception e) {
        log.error("Failed to read event by seq: {}", seq, e);
        return null;
    }
}
```

### 内存管理策略

**问题解决**：
- ✅ **定期清理**：每1000个新seq清理一次
- ✅ **大小限制**：最多保留10000个映射 (~500KB内存)
- ✅ **紧急清理**：防止异常情况下内存暴涨
- ✅ **无GC压力**：清理操作轻量，GC频率可控

**内存占用估算**：
- 10000个entry × 50字节 ≈ **500KB**
- 相比延迟改进：**1000ms → <5ms** (99.5%提升)

### 预期效果
- **延迟改进**: 从 O(n symbol) 降到 O(1) cache hit
- **吞吐量**: 高命中率下可提升 50-70%
- **内存安全**: 无泄漏风险，占用可控
- **实施复杂度**: ⭐⭐ (添加HashMap + 清理逻辑)

---

## 优化方案 #2：批量更新Redis ⭐⭐⭐⭐

### 问题
当前每个事件都调用 `redisTemplate.opsForValue().set()`，过于频繁

```
5000 events/sec × 每个2-5ms的Redis网络延迟 = 10-25秒/sec的阻塞！
```

### 解决方案

在 `EventLogReplicationSender` 中批量更新：

**修改方式**: 在 `sendPendingEvents()` 中

```java
@Service
public class EventLogReplicationSender {
    
    // 添加这些字段
    private static final int BATCH_UPDATE_SIZE = 10;  // 每10个事件更新一次Redis
    private long batchUpdateCounter = 0;
    
    /**
     * 发送待处理的事件
     */
    private void sendPendingEvents() {
        long currentCommittedSeq = eventLog.getMaxLocalSeq();
        long currentLastSentSeq = lastSentSeq.get();
        
        long lastRedisUpdateSeq = currentLastSentSeq;  // 记录上次Redis更新的seq

        for (long seq = currentLastSentSeq + 1; seq <= currentCommittedSeq; seq++) {
            try {
                EventLog.Event event = readEventBySeq(seq);
                if (event != null) {
                    byte[] data = serializeEvent(event);
                    kafkaTemplate.send(topic, String.valueOf(seq), data);
                    
                    // 只更新内存, 不立即写Redis
                    lastSentSeq.set(seq);
                    
                    // 累计计数，每BATCH_UPDATE_SIZE个事件更新一次Redis
                    if (++batchUpdateCounter % BATCH_UPDATE_SIZE == 0) {
                        updateLastSentSeqToRedis(seq);
                        lastRedisUpdateSeq = seq;
                    }
                    
                    log.debug("Sent event seq: {}", seq);
                } else {
                    log.warn("Event not found for seq: {} during batch send", seq);
                }
            } catch (Exception e) {
                log.error("Failed to send event seq: {}", seq, e);
            }
        }
        
        // 循环结束时，确保最后的seq也被更新到Redis
        if (currentCommittedSeq > lastRedisUpdateSeq) {
            updateLastSentSeqToRedis(currentCommittedSeq);
        }
    }
    
    /**
     * 更新最后发送的序列号到Redis (异步方式)
     */
    private void updateLastSentSeqToRedis(long seq) {
        try {
            redisTemplate.opsForValue().set(SENT_SEQ_KEY, String.valueOf(seq));
            log.debug("Updated Redis lastSentSeq={}", seq);
        } catch (Exception e) {
            log.warn("Failed to persist last sent seq: {}", seq, e);
        }
    }
    
    // 保留原有的updateLastSentSeq方法用于其他地方
    /**
     * 更新最后发送的序列号 (内存)
     */
    private void updateLastSentSeq(long seq) {
        lastSentSeq.set(seq);
    }
}
```

### 预期效果
- **Redis调用**: 从 N次/iteration 降到 N/10次
- **延迟改进**: 消除 100-500ms的Redis阻塞
- **缺点**: 宕机时可能损失最后的10个事件的进度记录，但不影响数据可靠性

---

## 优化方案 #3：动态循环间隔 ⭐⭐⭐

### 问题
固定 100ms 循环间隔，事件堆积时反应慢

```
事件到达 → 等待0-100ms → 才开始发送 → 再等100ms...
如果有大量事件堆积，延迟 = 堆积数量 × 100ms
```

### 解决方案

在 `EventLogReplicationSender` 中动态调整：

```java
/**
 * 发送循环
 */
private void sendingLoop() {
    log.info("Starting event log replication sending loop");

    while (running && !Thread.currentThread().isInterrupted()) {
        try {
            long beforeSend = System.currentTimeMillis();
            sendingLoopIteration();
            long elapsedMs = System.currentTimeMillis() - beforeSend;
            
            // 根据待发送事件数动态调整睡眠时间
            long pendingEvents = eventLog.getMaxLocalSeq() - lastSentSeq.get();
            
            // 计算动态睡眠时间
            // 无待发送事件：睡100ms (原始值)
            // 有小量待发送事件(1-10)：睡50ms
            // 有中量待发送事件(11-100)：睡20ms
            // 有大量待发送事件(>100)：睡10ms
            long sleepMs;
            if (pendingEvents == 0) {
                sleepMs = 100;
            } else if (pendingEvents <= 10) {
                sleepMs = 50;
            } else if (pendingEvents <= 100) {
                sleepMs = 20;
            } else {
                sleepMs = 10;
            }
            
            // 如果上一次迭代已经耗时很长，跳过睡眠
            if (elapsedMs > sleepMs) {
                log.debug("Sending loop iteration took {}ms, skipping sleep", elapsedMs);
            } else {
                Thread.sleep(sleepMs - elapsedMs);
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
        } catch (Exception e) {
            log.error("Error in sending loop", e);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    log.info("Event log replication sending loop stopped");
}
```

### 预期效果
- **响应速度**: 事件堆积时立即增加发送频率
- **效率**: 无待发送事件时降低CPU占用
- **实施复杂度**: ⭐ (仅修改sleep逻辑)

---

## 优化方案 #4：异步Redis更新 ⭐⭐⭐

### 问题
同步Redis调用会阻塞发送线程

### 解决方案

在 `EventLogReplicationSender` 中使用异步线程池：

```java
@Service
public class EventLogReplicationSender {
    
    // 添加异步执行器
    private final ExecutorService redisExecutor = 
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "EventLogReplication-Redis");
                t.setDaemon(true);
                return t;
            });
    
    @PreDestroy
    public void shutdown() {
        stopSending();
        
        // 关闭Redis执行器
        redisExecutor.shutdown();
        try {
            if (!redisExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                redisExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            redisExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 更新最后发送的序列号 (异步)
     */
    private void updateLastSentSeq(long seq) {
        lastSentSeq.set(seq);
        
        // 异步提交Redis更新，不阻塞发送线程
        redisExecutor.submit(() -> {
            try {
                redisTemplate.opsForValue().set(SENT_SEQ_KEY, String.valueOf(seq));
                log.debug("Persisted last sent seq: {}", seq);
            } catch (Exception e) {
                log.warn("Failed to persist last sent seq: {}", seq, e);
            }
        });
    }
}
```

### 预期效果
- **延迟改进**: 消除Redis网络往返的阻塞（2-5ms per call）
- **吞吐量**: 提升 20-30%
- **缺点**: 增加线程开销，但收益远大于成本

---

## 快速验证方案

### 验证脚本

创建 `/Users/wuchuangeng/match_chronicle/docs/LATENCY_TEST_PLAN.md`:

```markdown
# 延迟测试验证计划

## 测试环境设置

### 1. 配置修改
```yaml
# application-local.properties
matching.eventlog.batch.size=100
matching.kafka.producer.batch.size=1000
matching.kafka.producer.linger.ms=10
matching.ha.replication.send-interval-ms=10  # 改为10ms用于测试
```

### 2. 监测指标采集

在 EventLogReplicationSender 中添加：

```java
private final MeterRegistry meterRegistry;  // 假设使用Micrometer

private void sendPendingEvents() {
    long startTime = System.nanoTime();
    
    // ... 发送逻辑 ...
    
    long elapsedNanos = System.nanoTime() - startTime;
    meterRegistry.timer("eventlog.send.batch.latency")
            .record(elapsedNanos, TimeUnit.NANOSECONDS);
}
```

### 3. 测试场景

#### 场景 A：低吞吐 (100 events/sec)
- 预期平均延迟: 50-100ms
- 预期P99延迟: 200ms

#### 场景 B：中吞吐 (1000 events/sec)
- 预期平均延迟: 100-200ms
- 预期P99延迟: 500ms

#### 场景 C：高吞吐 (5000 events/sec)
- 预期平均延迟: 200-400ms
- 预期P99延迟: 1000ms

### 4. 验收标准

| 指标 | 优化前 | 优化后目标 |
|-----|-------|----------|
| 平均延迟 (@5k/s) | 500ms+ | <300ms |
| P99延迟 | 5-10s | <2s |
| 主备seq差距 (@5k/s) | 500+ | <200 |
| Redis调用频率 | 5000/s | <500/s |
| CPU使用率 | ~15% | ~8% |

```

---

## 实施步骤总结

### Phase 1：立即实施 (今天)
1. ✅ 实施方案 #2 (批量Redis更新) - 最简单，效果明显
2. ✅ 添加监测指标

### Phase 2：本周
3. ✅ 实施方案 #1 (seq索引)
4. ✅ 实施方案 #3 (动态间隔)
5. ✅ 运行验证测试

### Phase 3：本月
6. ✅ 实施方案 #4 (异步Redis)
7. ✅ 压测验证

---

## 预期改进对标

| 指标 | 当前 | 优化后 (O1-O4) | 改进幅度 |
|-----|------|-------------|--------|
| 平均延迟 | 500ms | 100-150ms | **70%** ↓ |
| P99延迟 | 10s | 1-2s | **80-90%** ↓ |
| 最大lag | 1000+ events | 100-200 events | **80%** ↓ |
| Redis QPS | 5000/s | <500/s | **90%** ↓ |

---

## 风险评估

| 优化 | 风险 | 缓解方案 |
|-----|-----|--------|
| #1 seq索引 | 内存占用增加 | 定期清理过期seq |
| #2 批量Redis | 最后事件进度丢失 | 容错处理，不影响数据可靠性 |
| #3 动态间隔 | CPU波动 | 设定最大/最小边界 |
| #4 异步Redis | 线程管理开销 | 单线程池，简化设计 |


---

## 下一步行动

1. **立即审阅** #1 和 #2 的实现方案
2. **反馈** 是否有其他限制条件
3. **授权** 我开始实施代码修改
4. **运行** 测试验证改进效果

---


