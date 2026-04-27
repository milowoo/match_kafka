package com.matching.service;

import com.matching.config.symbol.SymbolConfigService;
import com.matching.dto.OrderBookEntry;
import com.matching.service.chronicle.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ChronicleQueueEventLog extends EventLog {
    private static final Logger log = LoggerFactory.getLogger(ChronicleQueueEventLog.class);

    private final AtomicLong committedSeq = new AtomicLong(0);
    private volatile OrderBookService orderBookService;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean orderBookServiceReady = new AtomicBoolean(false);

    // seq到symbol的索引映射，用于加速readEventBySeq
    private final ConcurrentHashMap<Long, String> seqToSymbolMap = new ConcurrentHashMap<>();
    private static final int MAX_MAP_SIZE = 100000;        // 扩大10倍，覆盖1.4秒数据量
    private static final int CLEANUP_BATCH_SIZE = 5000;    // 清理频率降低
    private static final int EMERGENCY_CLEANUP_SIZE = 150000;  // 紧急清理阈值
    private long cleanupCounter = 0;

    // 工具类组件
    private final ChronicleQueueComponentManager componentManager;
    private final ChronicleQueueRetryHandler retryHandler;
    private final ChronicleQueueTransactionManager transactionManager;
    private final ChronicleQueueHAOperations haOperations;
    private final SymbolConfigService symbolConfigService;
    private final EventLogReplicationMetrics metrics;

    public ChronicleQueueEventLog(StringRedisTemplate redisTemplate,
                                  ChronicleQueueFactory queueFactory,
                                  ChronicleQueueComponentManager componentManager,
                                  ChronicleQueueRetryHandler retryHandler,
                                  ChronicleQueueTransactionManager transactionManager,
                                  ChronicleQueueHAOperations haOperations,
                                  SymbolConfigService symbolConfigService,
                                  EventLogReplicationMetrics metrics) {
        super(redisTemplate);
        this.componentManager = componentManager;
        this.retryHandler = retryHandler;
        this.transactionManager = transactionManager;
        this.haOperations = haOperations;
        this.symbolConfigService = symbolConfigService;
        this.metrics = metrics;
    }

    /**
     * 延迟注入OrderBookService避免循环依赖
     */
    public void setOrderBookService(OrderBookService orderBookService) {
        this.orderBookService = orderBookService;
        this.orderBookServiceReady.set(true);

        // 为所有已创建的组件设置OrderBookService
        for (ChronicleQueueComponentManager.SymbolQueueComponents components :
                componentManager.getAllComponents().values()) {
            components.haManager.setOrderBookService(orderBookService);
        }

        log.info("OrderBookService injected successfully to {} symbol components",
                componentManager.getAllComponents().size());
    }

    @PostConstruct
    @Override
    public void init() {
        try {
            log.info("Initializing ChronicleQueueEventLog with modular components");
            componentManager.initialize();
            initialized.set(true);

            // 预热索引：扫描最近已提交的事件，建立初始映射
            // 这是为了应对重启/OOM后的性能问题
            warmUpSeqToSymbolIndex();

            log.info("ChronicleQueueEventLog initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize Chronicle Queue EventLog", e);
            initialized.set(false);
            throw new RuntimeException("Chronicle Queue EventLog initialization failed", e);
        }
    }

    @PreDestroy
    @Override
    public void shutdown() {
        log.info("Shutting down ChronicleQueueEventLog...");
        initialized.set(false);
        componentManager.shutdown();
        log.info("ChronicleQueueEventLog shutdown completed");
    }

    @Override
    public long appendBatch(String symbolId, List<OrderBookEntry> addedOrders,
                           List<String> removedOrderIds,
                           List<MatchResultEntry> matchResults) {
        ensureInitialized();

        // 获取该交易对的组件
        ChronicleQueueComponentManager.SymbolQueueComponents components =
                componentManager.getOrCreateComponents(symbolId, transactionManager.isTransactionEnabled(),
                        globalSeq, committedSeq, orderBookService);

        if (!components.queueManager.isPrimary()) {
            return globalSeq.get();
        }

        if ((addedOrders == null || addedOrders.isEmpty())
                && (removedOrderIds == null || removedOrderIds.isEmpty())
                && (matchResults == null || matchResults.isEmpty())) {
            return globalSeq.get();
        }

        // 预分配序列号
        long seq = transactionManager.allocateSequence(globalSeq);
        Event event = new Event(seq, symbolId, addedOrders, removedOrderIds, matchResults);

        try {
            // 使用重试处理器写入事件
            retryHandler.writeEventWithRetry(components, event, symbolId, seq);

            // 在成功写入后，记录seq->symbol映射
            seqToSymbolMap.putIfAbsent(seq, symbolId);

            // 定期清理旧映射，避免内存泄漏
            if (++cleanupCounter % CLEANUP_BATCH_SIZE == 0) {
                cleanupOldMappings(seq);
            }

            // 更新已提交序列号
            transactionManager.updateCommittedSeq(committedSeq, seq);

            // 检查点管理
            transactionManager.checkpointIfNeeded(components, globalSeq);

            return seq;
        } catch (Exception e) {
            // 回滚全局序列号
            transactionManager.rollbackGlobalSeq(globalSeq, seq);
            log.error("[ChronicleQueue] Failed to write event for symbol {}, seq: {} rolled back",
                    symbolId, seq, e);
            throw new ChronicleQueueWriteException(symbolId, seq, "Chronicle Queue write failed", e);
        }
    }

    @Override
    public List<Event> readEvents(String symbolId, long afterSeq) {
        ensureInitialized();

        try {
            ChronicleQueueComponentManager.SymbolQueueComponents components =
                    componentManager.getOrCreateComponents(symbolId, transactionManager.isTransactionEnabled(),
                            globalSeq, committedSeq, orderBookService);

            List<Event> events = components.reader.readEvents(symbolId, afterSeq);
            log.debug("Read {} events for symbol: {} after seq: {}",
                    events != null ? events.size() : 0, symbolId, afterSeq);
            return events != null ? events : new java.util.ArrayList<>();
        } catch (Exception e) {
            log.error("Failed to read events for symbol: {} after seq: {}", symbolId, afterSeq, e);
            return new java.util.ArrayList<>();
        }
    }

    @Override
    public void writeSnapshot(String symbolId, long seq, List<OrderBookEntry> allOrders) {
        ensureInitialized();

        ChronicleQueueComponentManager.SymbolQueueComponents components =
                componentManager.getOrCreateComponents(symbolId, transactionManager.isTransactionEnabled(),
                        globalSeq, committedSeq, orderBookService);

        if (!components.queueManager.isPrimary()) {
            return;
        }

        try {
            Snapshot snapshot = new Snapshot(seq, symbolId, allOrders);
            components.writer.writeSnapshot(snapshot);
            log.debug("Snapshot written for symbol: {}, seq: {}, orders: {}",
                    symbolId, seq, allOrders != null ? allOrders.size() : 0);
        } catch (Exception e) {
            log.error("Failed to write snapshot for symbol: {}, seq: {}", symbolId, seq, e);
        }
    }

    @Override
    public Snapshot readSnapshot(String symbolId) {
        ensureInitialized();

        try {
            ChronicleQueueComponentManager.SymbolQueueComponents components =
                    componentManager.getOrCreateComponents(symbolId, transactionManager.isTransactionEnabled(),
                            globalSeq, committedSeq, orderBookService);

            Snapshot snapshot = components.reader.readSnapshot(symbolId);
            log.debug("Snapshot read for symbol: {}, found: {}", symbolId, snapshot != null);
            return snapshot;
        } catch (Exception e) {
            log.error("Failed to read snapshot for symbol: {}", symbolId, e);
            return null;
        }
    }

    // HA操作方法 - 委托给HAOperations
    public synchronized void switchToPrimary(String symbolId) {
        ensureInitialized();
        haOperations.switchToPrimary(symbolId, orderBookService);
    }

    public synchronized void switchAllToPrimary() {
        ensureInitialized();
        haOperations.switchAllToPrimary(orderBookService);
    }

    public synchronized void switchToStandby(String symbolId) {
        ensureInitialized();
        haOperations.switchToStandby(symbolId);
    }

    public synchronized void switchAllToStandby() {
        ensureInitialized();
        haOperations.switchAllToStandby();
    }

    public String getRole(String symbolId) {
        ensureInitialized();
        return haOperations.getRole(symbolId);
    }

    public long getQueueSize(String symbolId) {
        ensureInitialized();
        ChronicleQueueComponentManager.SymbolQueueComponents components =
                componentManager.getComponents(symbolId);
        return components != null ? components.metricsCollector.getQueueSize() : 0;
    }

    public long currentSeq(String symbolId) {
        ensureInitialized();
        ChronicleQueueComponentManager.SymbolQueueComponents components =
                componentManager.getComponents(symbolId);
        return components != null ? components.metricsCollector.getCurrentSeq() : 0;
    }

    // 兼容性方法
    public long getCurrentSeq() {
        return globalSeq.get();
    }

    public long getCurrentSeq(String symbolId) {
        return currentSeq(symbolId);
    }

    public List<Event> readEventsAfter(long afterSeq) {
        return new java.util.ArrayList<>();
    }

    @Override
    public void appendReplicated(Event event) {
        ensureInitialized();

        long eventSeq = event.getSeq();

        // seq 幂等去重：eventSeq <= committedSeq 说明该事件已写入，直接跳过
        // 场景4重启补发或网络重试时可能收到重复消息，通过此处拦截避免重复写入 Chronicle Queue
        if (eventSeq <= committedSeq.get()) {
            log.debug("[Replication] Skip duplicate seq={} symbol={}, committedSeq={}",
                    eventSeq, event.getSymbolId(), committedSeq.get());
            return;
        }

        try {
            // 获取该交易对的组件
            ChronicleQueueComponentManager.SymbolQueueComponents components =
                    componentManager.getOrCreateComponents(event.getSymbolId(),
                            transactionManager.isTransactionEnabled(),
                            globalSeq, committedSeq, orderBookService);

            // 使用重试处理器写入事件(保留主实例的原始seq)
            retryHandler.writeEventWithRetry(components, event, event.getSymbolId(), eventSeq);

            // 在复制事件时，记录seq->symbol映射
            seqToSymbolMap.putIfAbsent(eventSeq, event.getSymbolId());

            // 复制时也定期清理
            if (++cleanupCounter % CLEANUP_BATCH_SIZE == 0) {
                cleanupOldMappings(eventSeq);
            }

            // 更新本地globalSeq为主实例的seq,确保主从seq一致
            if (eventSeq > globalSeq.get()) {
                globalSeq.set(eventSeq);
                log.debug("[Replication] Updated globalSeq to {} (replicated event seq: {})",
                        eventSeq, eventSeq);
            }

            // 更新已提交序列号
            transactionManager.updateCommittedSeq(committedSeq, eventSeq);

            log.debug("[Replication] Appended replicated event seq={} symbol={}",
                    eventSeq, event.getSymbolId());
        } catch (Exception e) {
            log.error("[Replication] Failed to append replicated event seq={} symbol={}",
                    event.getSeq(), event.getSymbolId(), e);
            throw new RuntimeException("Failed to append replicated event", e);
        }
    }

    public List<Event> readEventsAfter(String symbolId, long afterSeq) {
        return readEvents(symbolId, afterSeq);
    }

    public void createCheckpoint(long seq) {
        log.debug("Checkpoint created at seq: {}", seq);
    }

    public long getLastCheckpointSeq(String symbolId) {
        return 0;
    }

    public long append(String symbolId, List<OrderBookEntry> addedOrders) {
        return appendBatch(symbolId, addedOrders, null, null);
    }

    public ChronicleQueueMetricsCollector.MetricsSnapshot getMetricsSnapshot(String symbolId) {
        ensureInitialized();
        ChronicleQueueComponentManager.SymbolQueueComponents components =
                componentManager.getComponents(symbolId);
        return components != null ? components.metricsCollector.getSnapshot() : null;
    }

    public ChronicleQueueFactory.FactoryStatus getFactoryStatus() {
        ensureInitialized();
        return haOperations.getHAStatus().factoryStatus;
    }

    public boolean isOrderBookServiceReady() {
        return orderBookServiceReady.get();
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    @Override
    public long getMaxLocalSeq() {
        ensureInitialized();
        return committedSeq.get();
    }

    private void ensureInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException("ChronicleQueueEventLog not initialized");
        }
    }

    @Override
    public Event readEventBySeq(long seq) {
        ensureInitialized();

        try {
            // 记录读取操作
            metrics.recordReadEvent();

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
            metrics.recordCacheMiss();  // 记录缓存miss
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
                            log.debug("Found event seq={} in symbol: {} (cached for future)", seq, activeSymbolId);
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

    /**
     * 清理旧的seq->symbol映射，避免内存泄漏
     */
    private void cleanupOldMappings(long currentSeq) {
        int currentSize = seqToSymbolMap.size();

        // 记录seqMap大小
        metrics.recordSeqMapSize(currentSize);

        // 正常清理：保留最近MAX_MAP_SIZE个seq
        if (currentSize > MAX_MAP_SIZE) {
            long minSeqToKeep = currentSeq - MAX_MAP_SIZE;

            // 计算要删除的数量
            int removed = 0;
            for (Long seq : seqToSymbolMap.keySet()) {
                if (seq < minSeqToKeep) {
                    removed++;
                }
            }

            // 执行删除
            seqToSymbolMap.entrySet().removeIf(entry -> entry.getKey() < minSeqToKeep);

            log.debug("Cleaned up {} old seq mappings, current size: {}", removed, seqToSymbolMap.size());
        }

        // 紧急清理：如果仍然过大，保留最近50%
        if (seqToSymbolMap.size() > EMERGENCY_CLEANUP_SIZE) {
            long emergencyMinSeq = currentSeq - (MAX_MAP_SIZE / 2);

            // 计算要删除的数量
            int removed = 0;
            for (Long seq : seqToSymbolMap.keySet()) {
                if (seq < emergencyMinSeq) {
                    removed++;
                }
            }

            // 执行删除
            seqToSymbolMap.entrySet().removeIf(entry -> entry.getKey() < emergencyMinSeq);

            log.warn("Emergency cleanup: removed {} mappings, current size: {}",
                    removed, seqToSymbolMap.size());
        }
    }

    /**
     * 预热seq到symbol的索引映射
     * 扫描最近已提交的事件，建立初始映射
     * 主要用于重启后快速恢复性能
     */
    private void warmUpSeqToSymbolIndex() {
        long startTime = System.currentTimeMillis();

        try {
            List<String> activeSymbols = symbolConfigService.getActiveSymbolIds();

            // 并行预热所有symbol (加快速度)
            activeSymbols.parallelStream().forEach(symbolId -> {
                try {
                    ChronicleQueueComponentManager.SymbolQueueComponents components =
                            componentManager.getOrCreateComponents(symbolId, transactionManager.isTransactionEnabled(),
                                    globalSeq, committedSeq, orderBookService);

                    List<Event> events = components.reader.readEvents(symbolId, committedSeq.get() - 1);
                    events.forEach(event ->
                        seqToSymbolMap.putIfAbsent(event.getSeq(), symbolId));

                    log.info("Warmed {} for symbol: {}", events.size(), symbolId);
                } catch (Exception e) {
                    log.warn("Failed to warm up index for symbol: {}", symbolId, e);
                }
            });

            long duration = System.currentTimeMillis() - startTime;
            log.info("Seq-to-symbol warmup completed in {}ms, indexed {} entries",
                    duration, seqToSymbolMap.size());

            // 如果预热超过5秒，记录警告
            if (duration > 5000) {
                log.warn("Warmup took {}ms, may impact initial performance", duration);
            }
        } catch (Exception e) {
            log.error("Failed to warmup seqToSymbolMap", e);
        }
    }
}
