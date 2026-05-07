package com.matching.disruptor;

import com.lmax.disruptor.EventHandler;
import com.matching.command.CancelOrderCommand;
import com.matching.command.PlaceOrderCommand;
import com.matching.dto.CancelOrderParam;
import com.matching.dto.PlaceOrderParam;
import com.matching.dto.CommandResult;
import com.matching.dto.MatchResult;
import com.matching.dto.SyncPayload;
import com.matching.enums.TradeCommandType;
import com.matching.service.AsyncDepthPublisher;
import com.matching.service.BatchFailureRecoveryService;
import com.matching.service.EventLogReplicationService;
import com.matching.service.EventLog;
import com.matching.service.IdempotentService;
import com.matching.service.MatchingMetrics;
import com.matching.service.OrderBookService;
import com.matching.service.ResultOutboxService;
import com.matching.service.SnapshotService;
import com.matching.service.TraceContext;
import com.matching.service.outbox.OutboxEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.support.Acknowledgment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

@Slf4j
public class MatchEventHandler implements EventHandler<MatchEvent> {

    private final PlaceOrderCommand placeOrderCommand;
    private final CancelOrderCommand cancelOrderCommand;
    private final OrderBookService orderBookService;
    private final EventLog eventLog;
    private volatile ResultSplitter resultSplitter;
    private final AsyncDepthPublisher depthPublisher;
    private final MatchingMetrics metrics;
    private final IdempotentService idempotentService;
    private final ResultOutboxService resultOutboxService;
    private final SnapshotService snapshotService;
    private final BatchFailureRecoveryService batchFailureRecoveryService;
    private final EventLogReplicationService eventLogReplicationService;
    private final ExecutorService asyncIoExecutor;
    private final com.matching.config.RedisHealthIndicator redisHealthIndicator;

    private final Map<String, Long> lastSnapshotTimeBySymbol = new HashMap<>();
    private static final long SNAPSHOT_INTERVAL_MS = 60_000L;
    private static final long DEPTH_SNAPSHOT_INTERVAL_MS = 5_000L;

    /**
     * 构造函数 - 初始化事件处理器及其依赖服务
     *
     * @param placeOrderCommand 下单命令处理器
     * @param cancelOrderCommand 撤单命令处理器
     * @param orderBookService 订单簿服务
     * @param eventLog EventLog持久化服务（Chronicle Queue）
     * @param depthPublisher 行情深度推送服务
     * @param metrics 性能指标收集服务
     * @param idempotentService 幂等性检查服务
     * @param resultOutboxService 结果投箱服务（确保消息至少投递一次）
     * @param snapshotService 快照服务（定期保存订单簿状态）
     * @param batchFailureRecoveryService 批量失败恢复服务
     * @param eventLogReplicationService EventLog复制服务（主从同步）
     * @param asyncIoExecutor 异步IO执行器（非关键性IO操作线程池）
     * @param redisHealthIndicator Redis健康检查指标
     */
    public MatchEventHandler(PlaceOrderCommand placeOrderCommand,
                             CancelOrderCommand cancelOrderCommand,
                             OrderBookService orderBookService,
                             EventLog eventLog,
                             AsyncDepthPublisher depthPublisher,
                             MatchingMetrics metrics,
                             IdempotentService idempotentService,
                             ResultOutboxService resultOutboxService,
                             SnapshotService snapshotService,
                             BatchFailureRecoveryService batchFailureRecoveryService,
                             EventLogReplicationService eventLogReplicationService,
                             ExecutorService asyncIoExecutor,
                             com.matching.config.RedisHealthIndicator redisHealthIndicator) {
        this.placeOrderCommand = placeOrderCommand;
        this.cancelOrderCommand = cancelOrderCommand;
        this.orderBookService = orderBookService;
        this.eventLog = eventLog;
        this.depthPublisher = depthPublisher;
        this.metrics = metrics;
        this.idempotentService = idempotentService;
        this.resultOutboxService = resultOutboxService;
        this.snapshotService = snapshotService;
        this.batchFailureRecoveryService = batchFailureRecoveryService;
        this.eventLogReplicationService = eventLogReplicationService;
        this.asyncIoExecutor = asyncIoExecutor;
        this.redisHealthIndicator = redisHealthIndicator;
    }

    /**
     * 设置结果分割器 - 用于按账户分割撮合结果
     *
     * @param resultSplitter 结果分割器实现，用于将撮合结果按账户分类序列化
     */
    public void setResultSplitter(ResultSplitter resultSplitter) {
        this.resultSplitter = resultSplitter;
    }

    /**
     * Disruptor事件处理主方法 - 处理撮合事件的核心逻辑
     *
     * @param event Disruptor事件对象，包含订单命令数据
     * @param sequence 事件在RingBuffer中的序列号
     * @param endOfBatch 是否是本批次的最后一个事件
     *
     * 处理流程（三阶段）：
     *
     * ===== Phase 1: 命令执行 =====
     * 1. 幂等性检查（防重复处理）
     * 2. 执行下单或撤单命令，生成撮合结果
     * 3. 构建EventLog条目
     *
     * ===== Phase 2: EventLog持久化（关键阶段）=====
     * 1. 将成交记录、订单簿变化写入EventLog（Chronicle Queue）
     * 2. 若失败，触发批量失败恢复，不ack消息让Kafka重投
     * 3. 持久化成功后立即ack Kafka消息
     *
     * ===== Phase 3: 异步IO操作（最优努力）=====
     * 1. EventLog复制给从实例（主从同步）
     * 2. Redis幂等性标记
     * 3. 发送成交结果到下游系统
     * 4. 推送行情深度更新
     * 5. 定期保存快照
     * 6. 收集性能指标
     *
     * 异常处理：
     * - Phase 1-2失败：不ack，让Kafka重投
     * - Phase 3失败：非关键性，已ack，记录日志继续
     *
     * 性能特性：
     * - Disruptor线程（Phase 1-2）：低延迟，禁止阻塞IO
     * - 异步线程池（Phase 3）：非关键IO操作，不影响Disruptor吞吐量
     *
     * @see TraceContext 用于追踪链路
     * @see EventLog 用于事件持久化
     * @see ResultSplitter 用于按账户分割成交结果
     */
    @Override
    public void onEvent(MatchEvent event, long sequence, boolean endOfBatch) {
        String symbolId = event.getSymbolId();
        Acknowledgment ack = event.getAck();
        long startNanos = System.nanoTime();
        boolean eventLogPersisted = false;
        TraceContext.start(event.getTraceId(), symbolId, null);

        try {
            CommandResult cmdResult = null;
            switch (event.getType()) {
                case PLACE_ORDER:
                    cmdResult = handlePlaceOrder(symbolId, event.getPlaceOrderParam());
                    break;
                case CANCEL_ORDER:
                    cmdResult = handleCancelOrder(event.getCancelOrderParam());
                    break;
            }

            if (cmdResult == null) {
                // Load from EventLog on idempotent skip - ack immediately
                if (ack != null) ack.acknowledge();
                return;
            }

            // ====== Phase 1: EventLog persist (critical) ======
            SyncPayload sp = cmdResult.getSyncPayload();
            String orderId = extractOrderId(event);

            // 一次序列化，同时用于 EventLog 持久化和 async IO 出站，避免重复
            Map<String, byte[]> serializedPerAccount = null;
            List<EventLog.MatchResultEntry> matchResultEntries;
            long seq = 0;
            try {
                serializedPerAccount = resultSplitter != null
                        ? resultSplitter.splitAndSerialize(symbolId, cmdResult.getMatchResult())
                        : Collections.emptyMap();
                matchResultEntries = toMatchResultEntries(serializedPerAccount);
                seq = eventLog.appendBatch(symbolId, sp.getAddedOrders(), sp.getRemovedOrderIds(), matchResultEntries);
            } catch (Exception e) {
                log.error("[{}] EventLog persist failed, initiating recovery, symbolId={}", symbolId, e);
                List<String> failedIds = orderId != null ? List.of(orderId) : List.of();
                batchFailureRecoveryService.performRecovery(symbolId, failedIds, e);
                // Not ack, let Kafka redeliver
                return;
            }

            // ====== Phase 2: EventLog persisted - ack immediately ======
            eventLogPersisted = true;
            if (ack != null) ack.acknowledge();

            metrics.recordMatch(symbolId, System.nanoTime() - startNanos);

            // ====== Phase 3: Best-effort async IO ======
            final long committedSeq = seq;
            final Map<String, Set<java.math.BigDecimal>> changedPriceLevels = sp.getChangedPriceLevels();
            final String committedOrderId = orderId;
            // 构建复制事件（在 Disruptor 线程捕获，避免 asyncIoExecutor 里访问已 reset 的 event）
            final EventLog.Event replicationEvent = new EventLog.Event(
                    committedSeq, symbolId, sp.getAddedOrders(), sp.getRemovedOrderIds(), matchResultEntries);
            // 复用 Phase 1 已完成的序列化结果，避免重复 splitAndSerialize
            final Map<String, byte[]> cachedSerialized = serializedPerAccount;

            asyncIoExecutor.execute(() -> {
                // EventLog 复制给从实例
                try {
                    eventLogReplicationService.replicateEvent(replicationEvent);
                } catch (Exception e) {
                    log.warn("[{}] EventLog replication failed (non-fatal), seq={}", symbolId, committedSeq, e);
                }

                // Redis idempotent mark
                if (committedOrderId != null && redisHealthIndicator.isHealthy()) {
                    try {
                        idempotentService.markProcessed(symbolId, committedOrderId);
                    } catch (Exception e) {
                        log.warn("[{}] Redis idempotent mark failed (non-fatal), symbolId={}", symbolId, e);
                    }
                }

                // Send match results to downstream (复用 Phase 1 的序列化结果)
                List<OutboxEntry> outboxEntries = toOutboxEntries(cachedSerialized);
                if (!outboxEntries.isEmpty()) {
                    try {
                        resultOutboxService.sendBatch(outboxEntries, () -> {
                            if (committedSeq > 0) eventLog.writeLastSentSeq(symbolId, committedSeq);
                        });
                    } catch (Exception e) {
                        log.error("[{}] Failed to send match results to downstream, symbolId={}", symbolId, e);
                    }
                }

                // Depth update (async via publisher's internal queue)
                if (changedPriceLevels != null && !changedPriceLevels.isEmpty()) {
                    try {
                        // Convert List to Set for publishDepthUpdate
                        Map<String, Set<java.math.BigDecimal>> changedPriceLevelsSet = changedPriceLevels.entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> new java.util.HashSet<>(e.getValue())
                            ));
                        depthPublisher.publishDepthUpdate(symbolId, orderBookService.getEngine(symbolId), changedPriceLevelsSet);
                    } catch (Exception e) {
                        log.error("[{}] Failed to publish depth update, symbolId={}", symbolId, e);
                    }
                }

                // Periodic snapshot (async - Redis write must not block Disruptor thread)
                long now = System.currentTimeMillis();
                if (now - lastSnapshotTimeBySymbol.getOrDefault(symbolId, 0L) > SNAPSHOT_INTERVAL_MS) {
                    lastSnapshotTimeBySymbol.put(symbolId, now);
                    try {
                        String depthSnapshotSymbol = symbolId;
                        asyncIoExecutor.execute(() -> {
                            try {
                                snapshotService.snapshot(depthSnapshotSymbol, orderBookService.getEngine(symbolId));
                            } catch (Exception ex) {
                                log.error("[{}] Failed to write snapshot, depthSnapshotSymbol={}", symbolId, ex);
                            }
                        });
                    } catch (Exception e) {
                        log.error("[{}] Failed to submit snapshot task, symbolId={}", symbolId, e);
                    }
                }

                // Collect order data on Disruptor thread, write Redis async
                try {
                    var engine = orderBookService.getEngine(symbolId);
                    metrics.recordOrderBookSize(symbolId, engine.getBuyBook().size(), engine.getSellBook().size(), engine.orderCount());
                } catch (Exception e) {
                    log.warn("[{}] Failed to record metrics, symbolId={}", symbolId, e);
                }
            });
        } catch (Exception e) {
            log.error("[{}] failed to process event, sequence: {}", symbolId, sequence, e);
            // Only ack if EventLog already persisted (Phase 2+3 error is non-fatal)
            if (eventLogPersisted) {
                if (ack != null) ack.acknowledge();
            } else {
                log.error("[{}] EventLog NOT persisted, not acking - Kafka will redeliver, sequence={}", symbolId, sequence);
            }
        } finally {
            event.reset();
            TraceContext.clear();
        }
    }

    /**
     * 处理下单命令 - 包含幂等性检查和命令执行
     *
     * @param symbolId 交易对ID
     * @param message 下单参数
     * @return 命令执行结果，若重复或幂等跳过则返回null
     *
     * 处理流程：
     * 1. 从订单ID提取或生成
     * 2. 检查订单簿中是否已存在（本地快速检查）
     * 3. 检查Redis幂等性标记（全局唯一性检查）
     * 4. 若重复则直接返回null，不再处理
     * 5. 执行下单命令（撮合逻辑）
     * 6. 标记为已处理（本地标记，后续会持久化到Redis）
     *
     * 幂等性保证：
     * - 下单命令带有唯一的订单号
     * - 同一订单号多次提交只会被处理一次
     * - 通过本地缓存+Redis实现分布式幂等
     *
     * 返回值：
     * - null: 订单重复（不处理）
     * - CommandResult: 正常处理结果（成功或拒绝）
     */
    private CommandResult handlePlaceOrder(String symbolId, PlaceOrderParam message) {
        String orderId = extractOrderIdFromMessage(message);
        if (orderBookService.getOrder(symbolId, orderId) != null) {
            log.warn("[{}] Duplicate orderNo detected in order book, skipping: {}", symbolId, orderId);
            return null;
        }

        if (idempotentService.isProcessed(symbolId, orderId)) {
            log.warn("[{}] Duplicate orderNo detected by idempotent check, skipping: {}", symbolId, orderId);
            return null;
        }

        CommandResult result = placeOrderCommand.execute(message);
        if (result != null) {
            idempotentService.markLocal(symbolId, orderId);
        }
        return result;
    }

    /**
     * 处理撤单命令 - 检查撤单是否产生实质变化
     *
     * @param cancelParam 撤单参数
     * @return 命令执行结果，若无变化或已撤销则返回null
     *
     * 处理流程：
     * 1. 执行撤单命令
     * 2. 检查是否产生实质变化：
     *    a) 订单簿中有订单被移除
     *    b) 成交订单列表非空
     * 3. 如果无变化，返回null（优化：不持久化无效操作）
     *
     * 返回值：
     * - null: 订单已撤销或不存在（无变化）
     * - CommandResult: 成功撤销或有成交的结果
     */
    private CommandResult handleCancelOrder(CancelOrderParam cancelParam) {
        CommandResult result = cancelOrderCommand.execute(cancelParam);
        if (result == null) {
            return null;
        }
        SyncPayload sp = result.getSyncPayload();
        boolean hasChanges = (sp.getAddedOrders() != null && !sp.getAddedOrders().isEmpty())
                || (sp.getRemovedOrderIds() != null && !sp.getRemovedOrderIds().isEmpty());
        if (!hasChanges && (result.getMatchResult() == null || result.getMatchResult().getDealtOrders().isEmpty())) {
            return null;
        }
        return result;
    }

    /**
     * 从Disruptor事件中提取订单ID
     * 
     * @param event Disruptor事件对象
     * @return 订单ID，若为撤单事件或无订单则返回null
     * 
     * 逻辑：
     * - 仅处理PLACE_ORDER类型事件
     * - 从订单列表中取第一个订单的订单号
     * - 若为撤单或无订单则返回null
     * 
     * 用途：
     * - EventLog持久化时记录关联的订单ID
     * - 幂等性检查和标记
     */
    private String extractOrderId(MatchEvent event) {
        if (event.getType() == TradeCommandType.PLACE_ORDER
                && event.getPlaceOrderParam() != null
                && event.getPlaceOrderParam().getOrderList() != null
                && !event.getPlaceOrderParam().getOrderList().isEmpty()) {
            return String.valueOf(event.getPlaceOrderParam().getOrderList().get(0).getOrderNo());
        }
        return null;
    }

    /**
     * 构建EventLog匹配结果条目 - 按账户分割成交结果
     * 
     * @param symbolId 交易对ID
     * @param matchResult 成交结果对象
     * @return EventLog条目列表（按账户聚合）
     * 
     * 处理流程：
     * 1. 调用结果分割器（ResultSplitter）
     * 2. 按账户ID聚合成交数据
     * 3. 序列化为字节数组（Protobuf格式）
     * 4. 包装为EventLog.MatchResultEntry
     * 
     * 设计目的：
     * - 按账户分割，便于主从复制时按账户分发
     * - 便于查询某个账户的成交记录
     * - 减少EventLog单条记录的大小
     * 
     * 异常处理：
     * - 若分割失败返回空列表（不中断处理，继续推进Phase 2）
     */
    private static List<EventLog.MatchResultEntry> toMatchResultEntries(Map<String, byte[]> perAccount) {
        if (perAccount == null || perAccount.isEmpty()) return Collections.emptyList();
        List<EventLog.MatchResultEntry> entries = new ArrayList<>(perAccount.size());
        for (Map.Entry<String, byte[]> ee : perAccount.entrySet()) {
            entries.add(new EventLog.MatchResultEntry(ee.getKey(), ee.getValue()));
        }
        return entries;
    }

    /**
     * 构建Outbox投箱条目 - 用于发送成交结果给下游系统
     * 
     * @param symbolId 交易对ID
     * @param matchResult 成交结果对象
     * @return Outbox条目列表（按账户聚合）
     * 
     * 处理流程：
     * 1. 调用结果分割器（ResultSplitter），同buildMatchResultEntries()
     * 2. 按账户ID转换为OutboxEntry
     * 3. 用于批量发送至Kafka或其他消息队列
     * 
     * 用途：
     * - 确保成交结果至少投递一次（Outbox Pattern）
     * - 避免消息丢失
     * - 支持重试机制
     * 
     * 异常处理：
     * - 若分割失败返回空列表
     */
    private static List<OutboxEntry> toOutboxEntries(Map<String, byte[]> perAccount) {
        if (perAccount == null || perAccount.isEmpty()) return Collections.emptyList();
        List<OutboxEntry> entries = new ArrayList<>(perAccount.size());
        for (Map.Entry<String, byte[]> ee : perAccount.entrySet()) {
            entries.add(new OutboxEntry(ee.getKey(), ee.getValue()));
        }
        return entries;
    }

    /**
     * 从下单参数中提取订单ID
     * 
     * @param message 下单参数对象
     * @return 订单ID，若无则返回null
     * 
     * 逻辑：
     * - 从订单列表中取第一个订单的订单号
     * - 转换为String格式
     * - 若列表为空或无订单号则返回null
     * 
     * 用途：
     * - 幂等性检查的关键字段
     * - 重复检测
     */
    private String extractOrderIdFromMessage(PlaceOrderParam message) {
        if (message == null || message.getOrderList() == null || message.getOrderList().isEmpty()) {
            return null;
        }
        Long orderNo = message.getOrderList().get(0).getOrderNo();
        return orderNo != null ? String.valueOf(orderNo) : null;
    }
}