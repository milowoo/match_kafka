package com.matching.service;

import com.matching.dto.OrderBookEntry;
import com.matching.engine.CompactOrderBookEntry;
import com.matching.engine.MatchEngine;
import com.matching.service.orderbook.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class OrderBookService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrderBookService.class);
    private final OrderBookManager orderBookManager;
    private final EventLogLoader eventLogLoader;
    private final OrderOperations orderOperations;
    private final LoadFailureHandler loadFailureHandler;
    private final EventLog eventLog;

    public OrderBookService(OrderBookManager orderBookManager,
                            @Lazy EventLogLoader eventLogLoader,
                            OrderOperations orderOperations,
                            LoadFailureHandler loadFailureHandler,
                            EventLog eventLog) {
        this.orderBookManager = orderBookManager;
        this.eventLogLoader = eventLogLoader;
        this.orderOperations = orderOperations;
        this.loadFailureHandler = loadFailureHandler;
        this.eventLog = eventLog;
    }

    public boolean exists(String symbolId) {
        return orderBookManager.exists(symbolId);
    }

    public MatchEngine getEngine(String symbolId) {
        return orderBookManager.getEngine(symbolId);
    }

    public Map<String, MatchEngine> getAllEngines() {
        return orderBookManager.getAllEngines();
    }

    public void reloadEngine(String symbolId) {
        log.info("Reloading engine for symbolId: {}", symbolId);
        orderBookManager.removeEngine(symbolId);
        loadFromEventLog(symbolId, true);
    }

    public void loadFromEventLog(String symbolId, boolean recoverMatchResults) {
        try {
            MatchEngine engine = orderBookManager.getEngine(symbolId);

            // 先尝试从 Snapshot + 增量 EventLog 恢复
            boolean snapshotRecovered = tryRecoverFromSnapshot(symbolId, engine);

            if (!snapshotRecovered) {
                // Snapshot恢复失败，使用全量EventLog恢复
                log.warn("Snapshot recovery failed for {}, falling back to full EventLog recovery", symbolId);
                eventLogLoader.loadFromEventLog(symbolId, engine, recoverMatchResults);
            }

            orderBookManager.markAsLoaded(symbolId);
            log.info("Loaded orderbook for symbolId: {}, orders: {}, poolAvailable: {}",
                    symbolId, engine.orderCount(), engine.poolAvailable());
        } catch (SecurityException | IllegalArgumentException e) {
            log.error("Configuration error loading orderbook for symbolId: {}", symbolId, e);
            throw new RuntimeException("Failed to load orderbook due to configuration error", e);
        } catch (OutOfMemoryError e) {
            log.error("Out of memory loading orderbook for symbolId: {}", symbolId, e);
            throw e;
        } catch (RuntimeException e) {
            log.error("Runtime error loading orderbook for symbolId: {}", symbolId, e);
            loadFailureHandler.handleLoadFailure(symbolId, "Runtime error", e);
        } catch (Exception e) {
            log.error("Unexpected error loading orderbook for symbolId: {}", symbolId, e);
            loadFailureHandler.handleLoadFailure(symbolId, "Unexpected error", e);
        }
    }

    /**
     * 尝试从Snapshot + 增量EventLog恢复
     */
    private boolean tryRecoverFromSnapshot(String symbolId, MatchEngine engine) {
        try {
            // 检查数据一致性
            EventLog.ConsistencyCheckResult consistencyResult = eventLog.checkDataConsistency(symbolId);
            log.info("Data consistency check for {}: status={}, localSeq={}, lastSentSeq={}, diff={}",
                    symbolId, consistencyResult.getStatus(), consistencyResult.getLocalSeq(),
                    consistencyResult.getLastSentSeq(), consistencyResult.getSeqDiff());

            // 如果数据缺失，不使用Snapshot恢复
            if (consistencyResult.getStatus() == EventLog.ConsistencyStatus.DATA_MISSING) {
                log.error("Data missing detected for {}, cannot use snapshot recovery", symbolId);
                return false;
            }

            // 尝试从Snapshot恢复
            EventLog.Snapshot snapshot = eventLog.readSnapshot(symbolId);
            if (snapshot == null) {
                log.info("No local snapshot found for {}, trying Redis snapshot", symbolId);
                // 尝试从Redis读取Snapshot
                return tryRecoverFromRedisSnapshot(symbolId, engine, consistencyResult);
            }

            // 使用本地Snapshot恢复
            return recoverFromLocalSnapshot(symbolId, engine, snapshot, consistencyResult);
        } catch (Exception e) {
            log.error("Failed to recover from snapshot for {}", symbolId, e);
            return false;
        }
    }

    /**
     * 从Redis Snapshot恢复
     */
    private boolean tryRecoverFromRedisSnapshot(String symbolId, MatchEngine engine,
                                               EventLog.ConsistencyCheckResult consistencyResult) {
        // 这里需要注入 SnapshotService，但为了避免循环依赖，暂时返回false
        log.info("Redis snapshot recovery not implemented yet for {}", symbolId);
        return false;
    }

    /**
     * 从本地Snapshot恢复
     */
    private boolean recoverFromLocalSnapshot(String symbolId, MatchEngine engine,
                                            EventLog.Snapshot snapshot,
                                            EventLog.ConsistencyCheckResult consistencyResult) {
        try {
            long snapshotSeq = snapshot.getSeq();
            long safeStartSeq = eventLog.getSafeRecoveryStartSeq(symbolId);

            // 检查Snapshot是否可用
            if (snapshotSeq > safeStartSeq) {
                log.warn("Snapshot seq {} > safe start seq {} for {}, using full recovery",
                        snapshotSeq, safeStartSeq, symbolId);
                return false;
            }

            // 加载Snapshot
            if (snapshot.getAllOrders() != null) {
                engine.loadOrders(snapshot.getAllOrders());
                log.info("Loaded {} orders from local snapshot for {}",
                        snapshot.getAllOrders().size(), symbolId);
            }

            // 使用新的过滤方法回放增量事件（处理seq不连续问题）
            List<EventLog.Event> incrementalEvents = eventLog.readEventsForSymbol(symbolId, snapshotSeq);
            int replayedEvents = 0;
            for (EventLog.Event event : incrementalEvents) {
                eventLogLoader.recoverFromEvent(event, engine);
                replayedEvents++;
                log.debug("Replayed event seq={} for {}", event.getSeq(), symbolId);
            }

            log.info("Successfully recovered {} from local snapshot seq={}, replayed {} incremental events",
                    symbolId, snapshotSeq, replayedEvents);
            return true;
        } catch (Exception e) {
            log.error("Failed to recover from local snapshot for {}", symbolId, e);
            return false;
        }
    }

    public void recoverFromEvent(EventLog.Event event) {
        if (event == null || event.getSymbolId() == null) {
            log.warn("Invalid event for recovery: {}", event);
            return;
        }

        String symbolId = event.getSymbolId();
        MatchEngine engine = orderBookManager.getEngine(symbolId);

        try {
            eventLogLoader.recoverFromEvent(event, engine);
            orderBookManager.markAsLoaded(symbolId);
        } catch (Exception e) {
            log.error("Failed to recover event for symbol: {}, seq: {}", symbolId, event.getSeq(), e);
            throw new RuntimeException("Event recovery failed for " + symbolId + ", seq: " + event.getSeq(), e);
        }
    }

    public CompactOrderBookEntry getCompactOrder(String symbolId, long orderId) {
        return orderOperations.getCompactOrder(symbolId, orderId);
    }

    public OrderBookEntry getOrder(String symbolId, String orderId) {
        return orderOperations.getOrder(symbolId, orderId);
    }

    public void removeOrder(String symbolId, long orderId) {
        orderOperations.removeOrder(symbolId, orderId);
    }

    public void clearSymbol(String symbolId) {
        orderBookManager.clearSymbol(symbolId);
        eventLog.clearSymbol(symbolId);
    }

    public Map<String, Set<Long>> cancelOrder(String symbolId, String orderId) {
        return orderOperations.cancelOrder(symbolId, orderId);
    }

    public OrderOperations.CancelAllResult cancelAllByAccount(String symbolId, long accountId) {
        return orderOperations.cancelAllByAccount(symbolId, accountId);
    }
}