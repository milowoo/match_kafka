package com.matching.service.orderbook;

import com.matching.dto.OrderBookEntry;
import com.matching.engine.CompactOrderBookEntry;
import com.matching.engine.MatchEngine;
import com.matching.service.EventLog;
import com.matching.service.EventLogReplicationService;
import com.matching.service.IdempotentService;
import com.matching.service.OrderBookService;
import com.matching.service.ResultOutboxService;
import com.matching.service.SnapshotService;
import com.matching.service.outbox.OutboxEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class EventLogLoader {

    private final EventLog eventLog;
    private final IdempotentService idempotentService;
    private final ResultOutboxService resultOutboxService;
    private final OrderBookService orderBookService;
    private final SnapshotService snapshotService;
    private final EventLogReplicationService eventLogReplicationService;

    public EventLogLoader(EventLog eventLog,
                           IdempotentService idempotentService,
                           ResultOutboxService resultOutboxService,
                           @Lazy OrderBookService orderBookService,
                           SnapshotService snapshotService,
                           EventLogReplicationService eventLogReplicationService) {
        this.eventLog = eventLog;
        this.idempotentService = idempotentService;
        this.resultOutboxService = resultOutboxService;
        this.orderBookService = orderBookService;
        this.snapshotService = snapshotService;
        this.eventLogReplicationService = eventLogReplicationService;
    }

    public void loadFromEventLog(String symbolId, MatchEngine engine, boolean recoverMatchResults) {
        long snapshotSeq = 0;

        // 1. Load snapshot from Redis
        EventLog.Snapshot snapshot = snapshotService.readSnapshotFromRedis(symbolId);
        if (snapshot != null) {
            snapshotSeq = snapshot.getSeq();
            if (snapshot.getAllOrders() != null) {
                engine.loadOrders(snapshot.getAllOrders());
            }
            log.info("[EventLog] Loaded snapshot for {}, seq: {}, orders: {}",
                    symbolId, snapshotSeq, snapshot.getAllOrders() != null ? snapshot.getAllOrders().size() : 0);
        }

        // 2. Replay events after snapshot: seq=snapshotSeq+1 到最新
        List<EventLog.Event> events = eventLog.readEvents(symbolId, snapshotSeq);
        long lastSentSeq = eventLog.readLastSentSeq(symbolId);
        List<OutboxEntry> recoveredResults = new ArrayList<>();

        // 场景4修复A：收集 lastSentSeq 到最新 seq 窗口内所有已处理订单的 orderId
        // 预热幂等缓存，防止重启后重复撮合
        // 特别针对已成交从 OrderBook 移除的订单（orderIndex 里已无记录，只能靠幂等缓存拦截）
        List<String> unsentWindowOrderIds = new ArrayList<>();

        for (EventLog.Event event : events) {
            if (event.getAddedOrders() != null) {
                engine.loadOrders(event.getAddedOrders());
            }
            if (event.getRemovedOrderIds() != null) {
                for (String orderId : event.getRemovedOrderIds()) {
                    engine.cancelOrder(Long.parseLong(orderId));
                }
            }

            // Only active recovers unsent match results to outbox
            if (recoverMatchResults && event.getSeq() > lastSentSeq && event.getMatchResults() != null) {
                for (EventLog.MatchResultEntry mr : event.getMatchResults()) {
                    recoveredResults.add(new OutboxEntry(mr.getUidKey(), mr.getPayload()));
                }
            }

            // 场景4修复A：收集未发送窗口内所有订单 orderId 用于幂等预热
            if (event.getSeq() > lastSentSeq) {
                if (event.getAddedOrders() != null) {
                    for (OrderBookEntry o : event.getAddedOrders()) {
                        if (o.getClientOrderId() != null) {
                            unsentWindowOrderIds.add(o.getClientOrderId());
                        }
                    }
                }
                if (event.getRemovedOrderIds() != null) {
                    unsentWindowOrderIds.addAll(event.getRemovedOrderIds());
                }
            }
        }

        if (!events.isEmpty()) {
            log.info("[EventLog] Replayed {} events for {}", events.size(), symbolId);
        }
        if (!recoveredResults.isEmpty()) {
            resultOutboxService.enqueueForRetry(recoveredResults);
            log.info("[EventLog] Recovered {} match results for retry for {}", recoveredResults.size(), symbolId);
        }

        // 3. Warm up idempotent LRU cache
        // 3a. 当前 OrderBook 里还在挂单的订单
        warmUpIdempotentCache(symbolId, engine);
        // 3b. 场景4修复A：未发送窗口内已成交/撤单从 OrderBook 移除的订单
        if (!unsentWindowOrderIds.isEmpty()) {
            idempotentService.markLocal(symbolId, unsentWindowOrderIds.toArray(new String[0]));
            log.info("[EventLog] Warmed up idempotent cache for unsent window: symbol={}, orderIds={}",
                    symbolId, unsentWindowOrderIds.size());
        }

        // 4. 场景4修复B：把 Redis Snapshot(seq=snapshotSeq) 之后的增量 EventLog 补发给从实例
        //    补发范围 = seq=snapshotSeq+1 到 seq=N（即本次回放的所有事件）
        //    这个窗口 = 最近一次 Snapshot 到 OOM 之间的数据，通常 60 秒内，数据量很小
        //    从实例已有 Snapshot 之前的数据（通过 Kafka eventlog-sync 正常复制），
        //    只需补发 Snapshot 之后的增量部分，确保从实例不漏数据
        if (!events.isEmpty()) {
            int replicated = 0;
            for (EventLog.Event event : events) {
                try {
                    eventLogReplicationService.replicateEvent(event);
                    replicated++;
                } catch (Exception e) {
                    log.warn("[EventLog] Failed to replicate event seq={} symbol={} during recovery",
                            event.getSeq(), symbolId, e);
                }
            }
            log.info("[EventLog] Replicated {} incremental events (seq={} to latest) to standby for symbol={}",
                    replicated, snapshotSeq + 1, symbolId);
        }
    }

    public void recoverFromEvent(EventLog.Event event, MatchEngine engine) {
        if (event == null || event.getSymbolId() == null) {
            log.warn("Invalid event for recovery: {}", event);
            return;
        }

        String symbolId = event.getSymbolId();

        if (event.getAddedOrders() != null && !event.getAddedOrders().isEmpty()) {
            engine.loadOrders(event.getAddedOrders());
            log.debug("Recovered {} added orders for symbol: {}, seq: {}",
                    event.getAddedOrders().size(), symbolId, event.getSeq());
        }

        if (event.getRemovedOrderIds() != null && !event.getRemovedOrderIds().isEmpty()) {
            for (String orderId : event.getRemovedOrderIds()) {
                try {
                    engine.cancelOrder(Long.parseLong(orderId));
                } catch (NumberFormatException e) {
                    log.warn("Invalid order ID format during recovery: {}", orderId);
                }
            }
            log.debug("Recovered {} removed orders for symbol: {}, seq: {}",
                    event.getRemovedOrderIds().size(), symbolId, event.getSeq());
        }

        if (event.getMatchResults() != null && !event.getMatchResults().isEmpty()) {
            long lastSentSeq = eventLog.readLastSentSeq(symbolId);
            if (event.getSeq() > lastSentSeq) {
                List<OutboxEntry> recoveredResults = new ArrayList<>();
                for (EventLog.MatchResultEntry mr : event.getMatchResults()) {
                    recoveredResults.add(new OutboxEntry(mr.getUidKey(), mr.getPayload()));
                }
                resultOutboxService.enqueueForRetry(recoveredResults);
                log.debug("Recovered {} match results for retry, symbol: {}, seq: {}",
                        recoveredResults.size(), symbolId, event.getSeq());
            }
        }
    }

    private void warmUpIdempotentCache(String symbolId, MatchEngine engine) {
        List<String> orderIds = new ArrayList<>();
        for (CompactOrderBookEntry e : engine.getBuyBook().allOrders()) {
            orderIds.add(String.valueOf(e.orderId));
        }
        for (CompactOrderBookEntry e : engine.getSellBook().allOrders()) {
            orderIds.add(String.valueOf(e.orderId));
        }

        if (!orderIds.isEmpty()) {
            idempotentService.markLocal(symbolId, orderIds.toArray(new String[0]));
            log.info("Warmed up idempotent LRU cache for symbol: {}, orderIds: {}", symbolId, orderIds.size());
        }
    }
}
