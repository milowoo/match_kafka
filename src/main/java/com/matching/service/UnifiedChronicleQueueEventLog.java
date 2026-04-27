package com.matching.service;

import com.matching.dto.OrderBookEntry;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.wire.WriteMarshallable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一的 Chronicle Queue 事件日志实现
 *
 * 设计特点：
 * - 单个统一的 Chronicle Queue，所有交易对共用
 * - 无 Per-Symbol 组件分散
 * - 无 seqToSymbolMap 索引映射，直接从事件字段读取 symbolId
 * - 支持按小时存放文件，Chronicle Queue 自动处理文件滚动
 *
 * 约束前提（与用户新的业务约束对齐）：
 * - 不需要业务隔离（交易对无需严格隔离）
 * - HA 按实例级别（不按交易对级别）
 * - 热门交易对通过部署隔离（不通过存储隔离）
 */
@Slf4j
@Service
public class UnifiedChronicleQueueEventLog extends EventLog {

    @Value("${matching.eventlog.dir:/mnt/shared/matching/eventlog-queue}")
    private String baseQueuePath;

    @Value("${matching.ha.instance-id:node-1}")
    private String instanceId;

    @Value("${matching.ha.role:STANDBY}")
    private String initialRole;

    // 单个统一的 Chronicle Queue
    private ChronicleQueue unifiedQueue;
    private ExcerptAppender appender;
    private ExcerptTailer tailer;

    // 用于跟踪已提交的最大序列号
    private final AtomicLong committedSeq = new AtomicLong(0);
    private final AtomicLong maxWriteSeq = new AtomicLong(0);

    // ================== HA 状态管理 ==================
    private final AtomicReference<String> currentRole = new AtomicReference<>("STANDBY");
    private volatile OrderBookService orderBookService;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean orderBookServiceReady = new AtomicBoolean(false);

    // ==================== 指标快照类 ====================
    public static class MetricsSnapshot {
        private final long queueSize;
        private final long currentSeq;
        private final String role;
        private final boolean healthy;
        private final long timestamp;

        public MetricsSnapshot(long queueSize, long currentSeq, String role, boolean healthy) {
            this.queueSize = queueSize;
            this.currentSeq = currentSeq;
            this.role = role;
            this.healthy = healthy;
            this.timestamp = System.currentTimeMillis();
        }

        public long getQueueSize() { return queueSize; }
        public long getCurrentSeq() { return currentSeq; }
        public String getRole() { return role; }
        public boolean isHealthy() { return healthy; }
        public long getTimestamp() { return timestamp; }
    }

    public UnifiedChronicleQueueEventLog(StringRedisTemplate redisTemplate) {
        super(redisTemplate);
    }

    // 测试友好的构造函数
    public UnifiedChronicleQueueEventLog(StringRedisTemplate redisTemplate, String initialRole, String instanceId) {
        super(redisTemplate);
        this.initialRole = initialRole;
        this.instanceId = instanceId;
        this.baseQueuePath = "/tmp/test-eventlog-" + System.currentTimeMillis();
        // 直接调用init()进行初始化
        init();
    }

    @PostConstruct
    @Override
    public void init() {
        try {
            log.info("Initializing UnifiedChronicleQueueEventLog with mode=UNIFIED");
            log.info("Base path: {}, instance: {}, role: {}", baseQueuePath, instanceId, initialRole);

            // 创建统一队列的目录
            String unifiedQueuePath = baseQueuePath + "/unified-queue";
            Path queueDir = Paths.get(unifiedQueuePath);
            Files.createDirectories(queueDir);

            log.info("Creating unified Chronicle Queue at: {}", unifiedQueuePath);

            // 创建单个统一的 Chronicle Queue
            this.unifiedQueue = ChronicleQueue.single(unifiedQueuePath);

            // 根据角色创建 appender（如果是 PRIMARY）
            if ("PRIMARY".equals(initialRole)) {
                this.appender = unifiedQueue.createAppender();
                log.info("Unified Queue initialized as PRIMARY, appender created");
            } else {
                log.info("Unified Queue initialized as STANDBY, no appender");
            }

            // 创建 tailer 用于读取
            String tailerName = "tailer-" + instanceId + "-" + System.currentTimeMillis();
            this.tailer = unifiedQueue.createTailer(tailerName);

            // 设置初始角色
            currentRole.set(initialRole);

            log.info("UnifiedChronicleQueueEventLog initialized successfully");

            // 标记初始化完成
            markInitialized();
        } catch (Exception e) {
            log.error("Failed to initialize Unified Chronicle Queue EventLog", e);
            throw new RuntimeException("Unified EventLog initialization failed", e);
        }
    }

    @PreDestroy
    @Override
    public void shutdown() {
        log.info("Shutting down UnifiedChronicleQueueEventLog...");
        try {
            if (appender != null) {
                appender.close();
                appender = null;
            }
            if (tailer != null) {
                tailer.close();
                tailer = null;
            }
            if (unifiedQueue != null) {
                unifiedQueue.close();
                unifiedQueue = null;
            }
            log.info("UnifiedChronicleQueueEventLog shutdown completed");
        } catch (Exception e) {
            log.warn("Error during UnifiedChronicleQueueEventLog shutdown", e);
        }
    }

    @Override
    public long appendBatch(String symbolId, List<OrderBookEntry> addedOrders,
                           List<String> removedOrderIds,
                           List<MatchResultEntry> matchResults) {
        try {
            if (appender == null) {
                log.warn("Appender not available, instance is not PRIMARY");
                return globalSeq.get();
            }

            // 检查是否有数据需要写入
            if ((addedOrders == null || addedOrders.isEmpty())
                    && (removedOrderIds == null || removedOrderIds.isEmpty())
                    && (matchResults == null || matchResults.isEmpty())) {
                return globalSeq.get();
            }

            // 预分配序列号
            long seq = globalSeq.incrementAndGet();

            // 创建事件
            Event event = new Event(seq, symbolId, addedOrders, removedOrderIds, matchResults);

            // 写入统一队列使用 writeDocument
            appender.writeDocument(new EventWriter(event, true));

            // 更新已提交序列号和最大写入序列号
            committedSeq.set(seq);
            maxWriteSeq.set(Math.max(maxWriteSeq.get(), seq));

            log.debug("Event appended: seq={}, symbol={}, addedOrders={}, removedOrderIds={}, matchResults={}",
                    seq, symbolId,
                    addedOrders != null ? addedOrders.size() : 0,
                    removedOrderIds != null ? removedOrderIds.size() : 0,
                    matchResults != null ? matchResults.size() : 0);

            return seq;
        } catch (Exception e) {
            log.error("Failed to append batch for symbol: {}", symbolId, e);
            throw new ChronicleQueueWriteException(symbolId, 0, "Unified queue write failed", e);
        }
    }

    @Override
    public void appendReplicated(Event event) {
        try {
            long eventSeq = event.getSeq();

            // 幂等去重：eventSeq <= committedSeq 说明该事件已写入，直接跳过
            if (eventSeq <= committedSeq.get()) {
                log.debug("[Replication] Skip duplicate seq={} symbol={}, committedSeq={}",
                        eventSeq, event.getSymbolId(), committedSeq.get());
                return;
            }

            if (appender == null) {
                log.warn("Appender not available for replication, instance is not PRIMARY");
                return;
            }

            // 写入复制的事件（保留主实例的原始seq）使用 writeDocument
            appender.writeDocument(new EventWriter(event, true));

            // 更新全局序列号为主实例的seq，确保主从seq一致
            if (eventSeq > globalSeq.get()) {
                globalSeq.set(eventSeq);
                log.debug("[Replication] Updated globalSeq to {} (replicated event seq: {})",
                        eventSeq, eventSeq);
            }

            // 更新已提交序列号
            committedSeq.set(Math.max(committedSeq.get(), eventSeq));
            maxWriteSeq.set(Math.max(maxWriteSeq.get(), eventSeq));

            log.debug("[Replication] Appended replicated event seq={} symbol={}",
                    eventSeq, event.getSymbolId());
        } catch (Exception e) {
            log.error("[Replication] Failed to append replicated event seq={} symbol={}",
                    event.getSeq(), event.getSymbolId(), e);
            throw new RuntimeException("Failed to append replicated event", e);
        }
    }

    @Override
    public List<Event> readEvents(String symbolId, long afterSeq) {
        List<Event> result = new ArrayList<>();
        try {
            try (ExcerptTailer readTailer = unifiedQueue.createTailer()) {
                readTailer.moveToIndex(afterSeq + 1);

                while (readTailer.readDocument(wireIn -> {
                    String type = wireIn.read("type").text();
                    if ("event".equals(type)) {
                        Event event = wireIn.read("event").object(Event.class);
                        if (event != null && symbolId.equals(event.getSymbolId())) {
                            result.add(event);
                        }
                    }
                })) {
                    // 继续读取
                }
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to read events for symbol: {} after seq: {}", symbolId, afterSeq, e);
            return result;
        }
    }

    @Override
    public Event readEventBySeq(long seq) {
        try {
            try (ExcerptTailer readTailer = unifiedQueue.createTailer()) {
                readTailer.toStart();

                AtomicReference<Event> result = new AtomicReference<>();
                while (result.get() == null && readTailer.readDocument(wireIn -> {
                    String type = wireIn.read("type").text();
                    if ("event".equals(type)) {
                        Event event = wireIn.read("event").object(Event.class);
                        if (event != null && event.getSeq() == seq) {
                            result.set(event);
                        }
                    }
                })) {
                    // 继续读取直到找到
                }
                return result.get();
            }
        } catch (Exception e) {
            log.error("Failed to read event by seq: {}", seq, e);
            return null;
        }
    }

    @Override
    public void writeSnapshot(String symbolId, long seq, List<OrderBookEntry> allOrders) {
        try {
            if (appender == null) {
                log.warn("Appender not available, cannot write snapshot for symbol: {}", symbolId);
                return;
            }

            Snapshot snapshot = new Snapshot(seq, symbolId, allOrders);
            appender.writeDocument(new SnapshotWriter(snapshot));
            log.debug("Snapshot written for symbol: {}, seq: {}, orders: {}",
                    symbolId, seq, allOrders != null ? allOrders.size() : 0);
        } catch (Exception e) {
            log.error("Failed to write snapshot for symbol: {}, seq: {}", symbolId, seq, e);
        }
    }

    @Override
    public Snapshot readSnapshot(String symbolId) {
        try {
            // 从统一队列中查找该交易对最近的快照
            try (ExcerptTailer readTailer = unifiedQueue.createTailer()) {
                final Snapshot[] snapshotHolder = new Snapshot[1];
                readTailer.toStart();

                while (readTailer.readDocument(wireIn -> {
                    String type = wireIn.read("type").text();
                    if ("snapshot".equals(type)) {
                        Snapshot snapshot = wireIn.read("snapshot").object(Snapshot.class);
                        if (snapshot != null && symbolId.equals(snapshot.getSymbolId())) {
                            Snapshot current = snapshotHolder[0];
                            if (current == null || snapshot.getSeq() > current.getSeq()) {
                                snapshotHolder[0] = snapshot;
                            }
                        }
                    }
                })) {
                    // 继续读取
                }
                return snapshotHolder[0];
            }
        } catch (Exception e) {
            log.error("Failed to read snapshot for symbol: {}", symbolId, e);
            return null;
        }
    }

    @Override
    public long getMaxLocalSeq() {
        return committedSeq.get();
    }

    /**
     * 获取已提交的最大序列号
     */
    public long getCommittedSeq() {
        return committedSeq.get();
    }

    /**
     * 获取队列规模（用于监控）
     */
    public long getQueueSize(String symbolId) {
        try {
            return committedSeq.get();
        } catch (Exception e) {
            log.warn("Failed to get queue size for symbol: {}", symbolId, e);
            return 0;
        }
    }

    /**
     * 获取当前序列号（兼容交易对级别调用，实际返回全局序列号）
     * 在统一架构中，所有交易对共享一个全局序列号
     */
    public long currentSeq(String symbolId) {
        return globalSeq.get();
    }

    /**
     * 获取实例角色（兼容交易对级别调用，实际返回实例级别角色）
     * 在统一架构中，角色是实例级别的，不是交易对级别的
     */
    public String getRole(String symbolId) {
        return currentRole.get();
    }

    /**
     * 获取指标快照（兼容交易对级别调用）
     */
    public MetricsSnapshot getMetricsSnapshot(String symbolId) {
        try {
            return new MetricsSnapshot(
                    committedSeq.get(),
                    globalSeq.get(),
                    currentRole.get(),
                    true
            );
        } catch (Exception e) {
            log.warn("Failed to get metrics snapshot for symbol: {}", symbolId, e);
            return new MetricsSnapshot(0, 0, currentRole.get(), false);
        }
    }

    /**
     * 切换所有交易对到主模式
     * 在统一架构中，这是实例级别的切换，而不是交易对级别的
     */
    public synchronized void switchAllToPrimary() {
        try {
            ensureInitialized();

            if ("PRIMARY".equals(currentRole.get())) {
                log.info("Already in PRIMARY role, skipping switch");
                return;
            }

            log.info("Switching unified queue to PRIMARY mode for instance: {}", instanceId);

            // 重新创建 appender
            if (appender == null && unifiedQueue != null) {
                appender = unifiedQueue.createAppender();
                log.info("Appender created for PRIMARY mode");
            }

            currentRole.set("PRIMARY");
            log.info("Instance {} switched to PRIMARY successfully", instanceId);
        } catch (Exception e) {
            log.error("Failed to switch to PRIMARY mode", e);
            throw new RuntimeException("Failed to switch to PRIMARY mode", e);
        }
    }

    /**
     * 切换所有交易对到从模式
     * 在统一架构中，这是实例级别的切换，而不是交易对级别的
     */
    public synchronized void switchAllToStandby() {
        try {
            log.info("Switching unified queue to STANDBY mode for instance: {}", instanceId);

            // 关闭 appender，停止写入
            if (appender != null) {
                appender.close();
                appender = null;
                log.info("Appender closed for STANDBY mode");
            }

            currentRole.set("STANDBY");
            log.info("Instance {} switched to STANDBY successfully", instanceId);
        } catch (Exception e) {
            log.error("Failed to switch to STANDBY mode", e);
            throw new RuntimeException("Failed to switch to STANDBY mode", e);
        }
    }

    /**
     * 设置 OrderBookService（用于依赖注入）
     * 在标准启动时由 Spring 容器调用，确保 HA 操作能访问订单簿
     */
    public void setOrderBookService(OrderBookService orderBookService) {
        this.orderBookService = orderBookService;
        this.orderBookServiceReady.set(true);
        log.info("OrderBookService set successfully for unified eventlog");
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * 检查 OrderBookService 是否就绪
     */
    public boolean isOrderBookServiceReady() {
        return orderBookServiceReady.get();
    }

    /**
     * 确保已初始化
     */
    private void ensureInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException("UnifiedChronicleQueueEventLog not initialized");
        }
    }

    /**
     * 标记为已初始化（在 init() 完成后调用）
     */
    private void markInitialized() {
        initialized.set(true);
        currentRole.set(initialRole);
    }

    // ==================== 内部 Writer 类 ====================

    private static class EventWriter implements WriteMarshallable {
        private final Event event;
        private final boolean committed;

        public EventWriter(Event event, boolean committed) {
            this.event = event;
            this.committed = committed;
        }

        @Override
        public void writeMarshallable(net.openhft.chronicle.wire.WireOut out) {
            out.write("type").text("event");
            out.write("event").object(event);
            out.write("committed").bool(committed);
        }
    }

    private static class SnapshotWriter implements WriteMarshallable {
        private final Snapshot snapshot;

        public SnapshotWriter(Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public void writeMarshallable(net.openhft.chronicle.wire.WireOut out) {
            out.write("type").text("snapshot");
            out.write("snapshot").object(snapshot);
        }
    }
}
