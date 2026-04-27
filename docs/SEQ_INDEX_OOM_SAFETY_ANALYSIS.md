# seqToSymbolMap 内存隐患分析与修复方案

## 问题分析

### 场景：主实例OOM或重启

```
时间轴：
T=0:    主实例正常运行，seqToSymbolMap中有10000条映射（seq 9001-19000）
T=1:    主实例发生OOM或被重启
T=2:    主实例启动恢复
        - seqToSymbolMap: 空 (JVM进程重新开始)
        - Redis中 SENT_SEQ_KEY: 保存了 seq=18500
        - Chronicle Queue: 仍有seq 18501-19000的未发送事件
        
T=3:    EventLogReplicationSender启动，开始发送未发送的事件
        - 需要读取 seq 18501-19000
        - 调用 readEventBySeq(18501)
        - seqToSymbolMap.get(18501) → null (缓存丢失)
        - 降级到全扫描：遍历所有symbol去找seq=18501
        
T=4:    灾难性后果
        - 每次都要全扫描所有symbol
        - 500个待发送事件 × 20个symbol = 10000次查询
        - 极度缓慢，从实例无法追上主实例
```

### 根本原因

seqToSymbolMap是纯内存缓存，不持久化：
- ✅ 优化场景：正常运行，缓存命中率高
- ❌ 危险场景：重启或OOM后，缓存全部丢失，性能退化

## 必要性验证

**这个fix是必须的吗？**

分析一下没有fix的情况：
1. **数据正确性**：❌ 没有问题，数据不会丢失
2. **可用性**：❌ 没有问题，系统仍能工作
3. **性能影响**：⚠️ **严重问题** - 重启后性能会极度下降

**结论**：这不是data loss问题，而是性能degradation问题。但在高吞吐

量场景下，这会导致从实例长时间延迟，可能触发主从不同步告警。

## 解决方案：三层防御

### 方案 A：懒加载重建索引（推荐，最简洁）

在 `init()` 时不做任何工作，让索引在使用时自然re-build：

```java
// 这就是当前的实现，实际上已经满足了这个需求
// readEventBySeq 的降级逻辑会自动重建缓存
// 问题是：初期会很慢

// 改进：添加预热
@PostConstruct
@Override
public void init() {
    try {
        log.info("Initializing ChronicleQueueEventLog with modular components");
        componentManager.initialize();
        initialized.set(true);
        
        // 预热索引：扫描最近已提交的事件，建立初始映射
        warmUpSeqToSymbolIndex();
        
        log.info("ChronicleQueueEventLog initialized successfully");
    } catch (Exception e) {
        log.error("Failed to initialize Chronicle Queue EventLog", e);
        initialized.set(false);
        throw new RuntimeException("Chronicle Queue EventLog initialization failed", e);
    }
}

/**
 * 预热seq->symbol索引，用于快速恢复
 * 避免重启后第一次发送时性能下降
 */
private void warmUpSeqToSymbolIndex() {
    try {
        long startSeq = Math.max(0, committedSeq.get() - 5000);  // 预热最近5000条
        long endSeq = committedSeq.get();
        
        log.info("Warming up seq-to-symbol index from {} to {}...", startSeq, endSeq);
        
        List<String> activeSymbols = symbolConfigService.getActiveSymbolIds();
        int indexedCount = 0;
        
        for (String symbolId : activeSymbols) {
            try {
                ChronicleQueueComponentManager.SymbolQueueComponents components =
                        componentManager.getOrCreateComponents(symbolId, transactionManager.isTransactionEnabled(),
                                globalSeq, committedSeq, orderBookService);
                
                List<Event> events = components.reader.readEvents(symbolId, startSeq);
                for (Event event : events) {
                    if (event.getSeq() >= startSeq && event.getSeq() <= endSeq) {
                        seqToSymbolMap.putIfAbsent(event.getSeq(), symbolId);
                        indexedCount++;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to warmup index for symbol: {}", symbolId, e);
            }
        }
        
        log.info("Seq-to-symbol index warm-up completed. Indexed: {} entries", indexedCount);
    } catch (Exception e) {
        log.warn("Failed to warm up seq-to-symbol index, will use lazy loading", e);
        // 不影响启动，降级到懒加载
    }
}
```

### 方案 B：添加诊断日志（必须）

在readEventBySeq中添加诊断日志，以便排查问题：

```java
@Override
public Event readEventBySeq(long seq) {
    ensureInitialized();

    try {
        // 第一步：尝试从索引快速查找 (O(1))
        String cachedSymbolId = seqToSymbolMap.get(seq);
        if (cachedSymbolId != null) {
            // 缓存命中
            ChronicleQueueComponentManager.SymbolQueueComponents components =
                    componentManager.getComponents(cachedSymbolId);
            if (components != null) {
                List<Event> events = components.reader.readEvents(cachedSymbolId, seq - 1);
                for (Event event : events) {
                    if (event.getSeq() == seq) {
                        return event;  // 快速路径，无日志
                    }
                }
            }
        }
        
        // 第二步：索引未命中，降级到全扫描（并更新索引）
        long scanStartTime = System.currentTimeMillis();
        boolean isFirstRead = cachedSymbolId == null && committedSeq.get() > 0;  // 可能是重启后首次读
        
        if (isFirstRead && seqToSymbolMap.isEmpty()) {
            // 重启后首次全扫描，输出警告
            log.warn("[SeqIndex-MISS] First full scan after restart/OOM. seq={} mapSize={} committedSeq={}", 
                    seq, seqToSymbolMap.size(), committedSeq.get());
        }
        
        List<String> activeSymbols = symbolConfigService.getActiveSymbolIds();
        for (String activeSymbolId : activeSymbols) {
            try {
                ChronicleQueueComponentManager.SymbolQueueComponents components =
                        componentManager.getOrCreateComponents(activeSymbolId, transactionManager.isTransactionEnabled(),
                                globalSeq, committedSeq, orderBookService);
                
                List<Event> events = components.reader.readEvents(activeSymbolId, seq - 1);
                for (Event event : events) {
                    if (event.getSeq() == seq) {
                        // 索引miss时更新映射，为后续调用优化
                        seqToSymbolMap.putIfAbsent(seq, activeSymbolId);
                        
                        long scanEndTime = System.currentTimeMillis();
                        long scanDuration = scanEndTime - scanStartTime;
                        
                        if (scanDuration > 100) {
                            log.debug("[SeqIndex-UPDATE] Slow scan detected: seq={} symbol={} duration={}ms", 
                                    seq, activeSymbolId, scanDuration);
                        } else {
                            log.debug("Found event seq={} in symbol: {} (re-indexed)", seq, activeSymbolId);
                        }
                        return event;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to read events for symbol: {} when looking for seq: {}", activeSymbolId, seq, e);
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

### 方案 C：添加监控指标（推荐）

添加Micrometer指标来监控索引效率：

```java
@Override
public Event readEventBySeq(long seq) {
    ensureInitialized();

    try {
        // 统计
        String cacheStatus = "MISS";
        long scanStartTime = System.currentTimeMillis();
        
        // 第一步：尝试从索引快速查找 (O(1))
        String cachedSymbolId = seqToSymbolMap.get(seq);
        if (cachedSymbolId != null) {
            cacheStatus = "HIT";
            ChronicleQueueComponentManager.SymbolQueueComponents components =
                    componentManager.getComponents(cachedSymbolId);
            if (components != null) {
                List<Event> events = components.reader.readEvents(cachedSymbolId, seq - 1);
                for (Event event : events) {
                    if (event.getSeq() == seq) {
                        // 记录指标
                        recordSeqIndexMetric(cacheStatus, System.currentTimeMillis() - scanStartTime);
                        return event;
                    }
                }
            }
        }
        
        // 第二步：索引未命中，降级到全扫描（并更新索引）
        List<String> activeSymbols = symbolConfigService.getActiveSymbolIds();
        for (String activeSymbolId : activeSymbols) {
            try {
                ChronicleQueueComponentManager.SymbolQueueComponents components =
                        componentManager.getOrCreateComponents(activeSymbolId, transactionManager.isTransactionEnabled(),
                                globalSeq, committedSeq, orderBookService);
                
                List<Event> events = components.reader.readEvents(activeSymbolId, seq - 1);
                for (Event event : events) {
                    if (event.getSeq() == seq) {
                        seqToSymbolMap.putIfAbsent(seq, activeSymbolId);
                        recordSeqIndexMetric(cacheStatus, System.currentTimeMillis() - scanStartTime);
                        return event;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to read events for symbol: {} when looking for seq: {}", activeSymbolId, seq, e);
            }
        }
        
        log.warn("Event not found for seq: {}", seq);
        recordSeqIndexMetric(cacheStatus, System.currentTimeMillis() - scanStartTime);
        return null;
    } catch (Exception e) {
        log.error("Failed to read event by seq: {}", seq, e);
        return null;
    }
}

private void recordSeqIndexMetric(String cacheStatus, long scanDurationMs) {
    // 如果你使用Micrometer
    // Timer.Sample sample = Timer.start();
    // ... operation ...
    // sample.stop(Timer.builder("eventlog.seq.index.lookup")
    //     .tag("status", cacheStatus)
    //     .register(meterRegistry));
}
```

## 防御总结

| 防御层 | 内容 | 作用 | 实施难度 |
|-------|-----|------|--------|
| **方案A** | 预热索引 | 减少重启后初期的全扫描 | 简单 |
| **方案B** | 诊断日志 | 快速发现问题 | 简单 |
| **方案C** | 监控指标 | 实时监控索引效率 | 中等 |

## 风险评级

| 场景 | 严重程度 | 说明 |
|-----|--------|------|
| 正常运行 | 无风险 ✅ | 缓存工作正常转 |
| 单次重启 | 低风险 ⚠️ | 初期性能下降，但可恢复 |
| 频繁OOM/重启 | 中风险 ⚠️⚠️ | 持续性能下降 |
| 高吞吐+频繁重启 | 高风险 ❌ | 从实例长期延迟，可能不同步 |

## 建议

✅ **必须实施**（3个方案都做）：
1. 方案A（预热）：避免首次启动后性能斜崖式下降
2. 方案B（诊断日志）：快速定位问题根因
3. 方案C（监控）：实时告警异常

✅ **额外建议**：
- 添加启动日志，记录seqToSymbolMap的初始状态
- 添加health check，报告索引效率
- 定期分析日志，查看MISS率趋势


