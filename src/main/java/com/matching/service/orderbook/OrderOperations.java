package com.matching.service.orderbook;

import com.matching.dto.OrderBookEntry;
import com.matching.engine.CompactOrderBookEntry;
import com.matching.engine.MatchEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Slf4j
public class OrderOperations {

    private final OrderBookManager orderBookManager;

    public OrderOperations(OrderBookManager orderBookManager) {
        this.orderBookManager = orderBookManager;
    }

    public void addEntry(String symbolId, CompactOrderBookEntry entry) {
        orderBookManager.getEngine(symbolId).addOrder(entry);
    }

    public CompactOrderBookEntry getCompactOrder(String symbolId, long orderId) {
        MatchEngine engine = orderBookManager.getAllEngines().get(symbolId);
        return engine != null ? engine.getOrder(orderId) : null;
    }

    public OrderBookEntry getOrder(String orderId) {
        long id = Long.parseLong(orderId);
        for (MatchEngine engine : orderBookManager.getAllEngines().values()) {
            CompactOrderBookEntry entry = engine.getOrder(id);
            if (entry != null) {
                return entry.toEntry(engine.getSymbolId());
            }
        }
        return null;
    }

    public OrderBookEntry getOrder(String symbolId, String orderId) {
        long id = Long.parseLong(orderId);
        MatchEngine engine = orderBookManager.getAllEngines().get(symbolId);
        if (engine != null) {
            CompactOrderBookEntry entry = engine.getOrder(id);
            if (entry != null) return entry.toEntry(symbolId);
        }
        return null;
    }

    public void removeOrder(String symbolId, long orderId) {
        MatchEngine engine = orderBookManager.getAllEngines().get(symbolId);
        if (engine != null) {
            CompactOrderBookEntry entry = engine.getOrder(orderId);
            if (entry != null) {
                engine.removeFilledOrder(entry);
            }
        }
    }

    public Map<String, Set<Long>> cancelOrder(String symbolId, String orderId) {
        MatchEngine engine = orderBookManager.getAllEngines().get(symbolId);
        if (engine == null) return Collections.emptyMap();

        long id = Long.parseLong(orderId);
        CompactOrderBookEntry entry = engine.cancelOrder(id);
        if (entry == null) return Collections.emptyMap();

        String side = entry.side == CompactOrderBookEntry.BUY ? "buy" : "sell";
        long price = entry.price;
        if (price == 0) return Collections.emptyMap();
        return Map.of(side, Set.of(price));
    }

    public CancelAllResult cancelAllByAccount(String symbolId, long accountId) {
        MatchEngine engine = orderBookManager.getAllEngines().get(symbolId);
        if (engine == null) return new CancelAllResult(Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());

        List<CompactOrderBookEntry> toCancel = new ArrayList<>();
        for (CompactOrderBookEntry e : engine.getBuyBook().allOrders()) {
            if (e.accountId == accountId) toCancel.add(e);
        }
        for (CompactOrderBookEntry e : engine.getSellBook().allOrders()) {
            if (e.accountId == accountId) toCancel.add(e);
        }

        if (toCancel.isEmpty()) return new CancelAllResult(Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());

        Map<String, Set<Long>> changedLevels = new HashMap<>();
        List<OrderSnapshot> snapshots = new ArrayList<>();
        List<String> removedOrderIds = new ArrayList<>();

        for (CompactOrderBookEntry entry : toCancel) {
            // 如果是冰山单，使用总量
            long quantity = entry.isIceberg ? entry.icebergTotalQuantity : entry.quantity;
            long remainingQty = entry.isIceberg ?
                    entry.remainingQty + entry.icebergHiddenQuantity :
                    entry.remainingQty;

            snapshots.add(new OrderSnapshot(entry.orderId, entry.accountId, quantity, remainingQty));

            String side = entry.side == CompactOrderBookEntry.BUY ? "buy" : "sell";
            changedLevels.computeIfAbsent(side, k -> new HashSet<>()).add(entry.price);
            removedOrderIds.add(String.valueOf(entry.orderId));

            engine.cancelOrder(entry.orderId);
        }

        return new CancelAllResult(changedLevels, snapshots, removedOrderIds);
    }

    public static class OrderSnapshot {
        public final long orderId;
        public final long accountId;
        public final long quantity;
        public final long remainingQty;

        public OrderSnapshot(long orderId, long accountId, long quantity, long remainingQty) {
            this.orderId = orderId;
            this.accountId = accountId;
            this.quantity = quantity;
            this.remainingQty = remainingQty;
        }
    }

    public static class CancelAllResult {
        public final Map<String, Set<Long>> changedLevels;
        public final List<OrderSnapshot> snapshots;
        public final List<String> removedOrderIds;

        public CancelAllResult(Map<String, Set<Long>> changedLevels,
                                List<OrderSnapshot> snapshots, List<String> removedOrderIds) {
            this.changedLevels = changedLevels;
            this.snapshots = snapshots;
            this.removedOrderIds = removedOrderIds;
        }
    }
}