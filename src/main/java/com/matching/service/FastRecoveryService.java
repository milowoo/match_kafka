package com.matching.service;

import com.matching.dto.OrderBookEntry;
import com.matching.engine.MatchEngine;
import com.matching.service.orderbook.OrderBookManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 快速数据恢复服务 - 基于 Snapshot + WAL 恢复策略：
 * 1. 读取最新 Snapshot (通常1分钟内的数据)
 * 2. 重放 Snapshot 后的 WAL 事件 (通常几十到几百个事件)
 * 3. 总恢复时间: 通常在1-5秒内完成
 */
@Service
public class FastRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(FastRecoveryService.class);

    private final EventLog eventLog;
    private final SnapshotService snapshotService;
    private final OrderBookManager orderBookManager;

    @Value("${matching.recovery.enabled:true}")
    private boolean recoveryEnabled;

    public FastRecoveryService(EventLog eventLog,
                                SnapshotService snapshotService,
                                OrderBookManager orderBookManager) {
        this.eventLog = eventLog;
        this.snapshotService = snapshotService;
        this.orderBookManager = orderBookManager;
    }

    /**
     * 快速恢复单个交易对
     * 步骤: 1. 读取最新Snapshot (O(1)操作) 2. 重放WAL增量事件 (通常<100个事件) 3. 验证恢复完整性
     */
    public FastRecoveryResult recoverSymbol(String symbol) {
        if (!recoveryEnabled) {
            return FastRecoveryResult.disabled();
        }

        long startTime = System.currentTimeMillis();
        log.info("开始快速恢复: symbol={}", symbol);

        try {
            // 1. 读取最新Snapshot
            SnapshotRecoveryInfo snapshotInfo = loadLatestSnapshot(symbol);

            // 2. 重放WAL增量事件
            WALRecoveryInfo walInfo = replayWALEvents(symbol, snapshotInfo.getSnapshotSeq());

            // 3. 验证恢复结果
            boolean verified = verifyRecovery(symbol, snapshotInfo, walInfo);

            long duration = System.currentTimeMillis() - startTime;

            if (verified) {
                // 标记为已加载，确保Disruptor的MatchEventHandler能通过OrderBookService.exists()找到
                orderBookManager.markAsLoaded(symbol);

                log.info("快速恢复成功: symbol={}, duration={}ms, snapshot_orders={}, wal_events={}",
                        symbol, duration, snapshotInfo.getOrderCount(), walInfo.getEventCount());

                return FastRecoveryResult.success(
                        duration,
                        snapshotInfo.getOrderCount(),
                        walInfo.getEventCount(),
                        snapshotInfo.getSnapshotSeq(),
                        walInfo.getLatestSeq()
                );
            } else {
                log.error("快速恢复验证失败: symbol={}", symbol);
                return FastRecoveryResult.failed("恢复验证失败");
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("快速恢复失败: symbol={}, duration={}ms", symbol, duration, e);
            return FastRecoveryResult.failed("恢复异常: " + e.getMessage());
        }
    }

    /**
     * 加载最新Snapshot
     */
    private SnapshotRecoveryInfo loadLatestSnapshot(String symbol) {
        log.debug("加载最新Snapshot: symbol={}", symbol);

        // 读取最新快照 from Redis
        EventLog.Snapshot snapshot = snapshotService.readSnapshotFromRedis(symbol);

        if (snapshot == null) {
            log.info("无Snapshot，创建空订单引擎: symbol={}", symbol);
            MatchEngine engine = orderBookManager.getEngine(symbol);
            return new SnapshotRecoveryInfo(0, 0, System.currentTimeMillis());
        }

        // 从Snapshot恢复订单引擎 (使用OrderBookManager的engine，确保Disruptor能访问)
        MatchEngine engine = orderBookManager.getEngine(symbol);
        List<OrderBookEntry> orders = snapshot.getAllOrders();

        if (orders != null && !orders.isEmpty()) {
            engine.loadOrders(orders);
            log.debug("从Snapshot加载订单: symbol={}, count={}, seq={}",
                    symbol, orders.size(), snapshot.getSeq());
        }

        return new SnapshotRecoveryInfo(
                snapshot.getSeq(),
                orders != null ? orders.size() : 0,
                System.currentTimeMillis()
        );
    }

    /**
     * 重放WAL增量事件
     */
    private WALRecoveryInfo replayWALEvents(String symbol, long fromSeq) {
        log.debug("重放WAL事件: symbol={}, fromSeq={}", symbol, fromSeq);

        MatchEngine engine = orderBookManager.getEngine(symbol);

        // 读取增量事件
        List<EventLog.Event> events = eventLog.readEvents(symbol, fromSeq);

        if (events.isEmpty()) {
            log.debug("无WAL增量事件: symbol={}", symbol);
            return new WALRecoveryInfo(0, fromSeq, System.currentTimeMillis());
        }

        long latestSeq = fromSeq;
        int processedEvents = 0;

        // 重放事件
        for (EventLog.Event event : events) {
            try {
                replayEvent(engine, event);
                latestSeq = event.getSeq();
                processedEvents++;
            } catch (Exception e) {
                log.error("重放WAL事件失败: symbol={}, seq={}", symbol, event.getSeq(), e);
                // 继续处理下一个事件
            }
        }

        log.debug("WAL事件重放完成: symbol={}, events={}, latest_seq={}",
                symbol, processedEvents, latestSeq);

        return new WALRecoveryInfo(processedEvents, latestSeq, System.currentTimeMillis());
    }

    /**
     * 重放单个事件 - 优化版本
     */
    private void replayEvent(MatchEngine engine, EventLog.Event event) {
        // 添加订单
        if (event.getAddedOrders() != null) {
            for (OrderBookEntry order : event.getAddedOrders()) {
                engine.addOrderFromDTO(order);
            }
        }

        // 取消订单
        if (event.getCancelledOrderIds() != null) {
            for (String orderIdStr : event.getCancelledOrderIds()) {
                try {
                    long orderId = Long.parseLong(orderIdStr);
                    engine.cancelOrder(orderId);
                } catch (NumberFormatException e) {
                    log.warn("无效订单ID: {}", orderIdStr);
                }
            }
        }

        // 撮合结果不需要重放到订单引擎
        // 因为订单状态已经在订单数据中体现
    }

    /**
     * 验证恢复结果
     */
    private boolean verifyRecovery(String symbol, SnapshotRecoveryInfo snapshotInfo, WALRecoveryInfo walInfo) {
        try {
            MatchEngine engine = orderBookManager.getAllEngines().get(symbol);
            if (engine == null) {
                return false;
            }

            // 基本完整性检查
            int currentOrderCount = engine.orderCount();

            // 检查订单簿结构完整性
            boolean buyBookValid = engine.getBuyBook() != null;
            boolean sellBookValid = engine.getSellBook() != null;

            if (!buyBookValid || !sellBookValid) {
                log.error("订单簿结构无效: symbol={}", symbol);
                return false;
            }

            // 检查价格排序
            if (!engine.getBuyBook().isEmpty()) {
                // 买盘应该是降序
                for (int i = 1; i < engine.getBuyBook().size(); i++) {
                    if (engine.getBuyBook().priceAt(i - 1) < engine.getBuyBook().priceAt(i)) {
                        log.error("买盘价格排序错误: symbol={}", symbol);
                        return false;
                    }
                }
            }

            if (!engine.getSellBook().isEmpty()) {
                // 卖盘应该是升序
                for (int i = 1; i < engine.getSellBook().size(); i++) {
                    if (engine.getSellBook().priceAt(i - 1) > engine.getSellBook().priceAt(i)) {
                        log.error("卖盘价格排序错误: symbol={}", symbol);
                        return false;
                    }
                }
            }

            log.debug("恢复验证通过: symbol={}, orders={}", symbol, currentOrderCount);
            return true;
        } catch (Exception e) {
            log.error("恢复验证异常: symbol={}", symbol, e);
            return false;
        }
    }

    /**
     * 批量恢复多个交易对
     */
    public Map<String, FastRecoveryResult> recoverAllSymbols(List<String> symbols) {
        Map<String, FastRecoveryResult> results = new HashMap<>();

        log.info("开始批量快速恢复: symbols={}", symbols);

        // 顺序恢复，避免并行流导致恢复顺序不确定和 OrderBookManager 并发写入问题
        for (String symbol : symbols) {
            try {
                FastRecoveryResult result = recoverSymbol(symbol);
                results.put(symbol, result);
            } catch (Exception e) {
                log.error("批量恢复失败: symbol={}", symbol, e);
                results.put(symbol, FastRecoveryResult.failed("批量恢复异常: " + e.getMessage()));
            }
        }

        long successCount = results.values().stream()
                .filter(FastRecoveryResult::isSuccess)
                .count();

        log.info("批量快速恢复完成: total={}, success={}, failed={}",
                symbols.size(), successCount, symbols.size() - successCount);

        return results;
    }

    /**
     * Snapshot恢复信息
     */
    private static class SnapshotRecoveryInfo {
        private final long snapshotSeq;
        private final int orderCount;
        private final long timestamp;

        public SnapshotRecoveryInfo(long snapshotSeq, int orderCount, long timestamp) {
            this.snapshotSeq = snapshotSeq;
            this.orderCount = orderCount;
            this.timestamp = timestamp;
        }

        public long getSnapshotSeq() { return snapshotSeq; }
        public int getOrderCount() { return orderCount; }
        public long getTimestamp() { return timestamp; }
    }

    /**
     * WAL恢复信息
     */
    private static class WALRecoveryInfo {
        private final int eventCount;
        private final long latestSeq;
        private final long timestamp;

        public WALRecoveryInfo(int eventCount, long latestSeq, long timestamp) {
            this.eventCount = eventCount;
            this.latestSeq = latestSeq;
            this.timestamp = timestamp;
        }

        public int getEventCount() { return eventCount; }
        public long getLatestSeq() { return latestSeq; }
        public long getTimestamp() { return timestamp; }
    }

    /**
     * 快速恢复结果
     */
    public static class FastRecoveryResult {
        private final boolean success;
        private final long durationMs;
        private final int snapshotOrders;
        private final int walEvents;
        private final long snapshotSeq;
        private final long latestSeq;
        private final String message;

        private FastRecoveryResult(boolean success, long durationMs, int snapshotOrders,
                                    int walEvents, long snapshotSeq, long latestSeq, String message) {
            this.success = success;
            this.durationMs = durationMs;
            this.snapshotOrders = snapshotOrders;
            this.walEvents = walEvents;
            this.snapshotSeq = snapshotSeq;
            this.latestSeq = latestSeq;
            this.message = message;
        }

        public static FastRecoveryResult success(long durationMs, int snapshotOrders, int walEvents,
                                                  long snapshotSeq, long latestSeq) {
            return new FastRecoveryResult(true, durationMs, snapshotOrders, walEvents,
                    snapshotSeq, latestSeq, "恢复成功");
        }

        public static FastRecoveryResult failed(String message) {
            return new FastRecoveryResult(false, 0, 0, 0, 0, 0, message);
        }

        public static FastRecoveryResult disabled() {
            return new FastRecoveryResult(true, 0, 0, 0, 0, 0, "快速恢复已禁用");
        }

        // Getters
        public boolean isSuccess() { return success; }
        public long getDurationMs() { return durationMs; }
        public int getSnapshotOrders() { return snapshotOrders; }
        public int getWalEvents() { return walEvents; }
        public long getSnapshotSeq() { return snapshotSeq; }
        public long getLatestSeq() { return latestSeq; }
        public String getMessage() { return message; }

        @Override
        public String toString() {
            return String.format("FastRecoveryResult{success=%s, duration=%dms, snapshot_orders=%d, wal_events=%d, message='%s'}",
                    success, durationMs, snapshotOrders, walEvents, message);
        }
    }
}