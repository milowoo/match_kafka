package com.matching.command;

import com.matching.constant.OrderStatus;
import com.matching.dto.*;
import com.matching.engine.CompactOrderBookEntry;
import com.matching.service.HAService;
import com.matching.service.OrderBookService;
import com.matching.service.orderbook.OrderOperations.CancelAllResult;
import com.matching.service.orderbook.OrderOperations.OrderSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
public class CancelOrderCommand {
    private static final Logger log = LoggerFactory.getLogger(CancelOrderCommand.class);

    private final OrderBookService orderBookService;
    private final HAService haService;

/**
 * Constructor for CancelOrderCommand class
 * @param orderBookService The OrderBookService instance to be used for order cancellation operations
 */
    public CancelOrderCommand(OrderBookService orderBookService, HAService haService) {
        this.orderBookService = orderBookService;
        this.haService = haService;
    }

    public CommandResult execute(CancelOrderParam cancelParam) {
        String symbolId = cancelParam.getSymbolId();

        // 检查是否在PRIMARY角色，STANDBY角色不应该处理订单
        if (!haService.isActive()) {
            log.warn("Not in PRIMARY role, rejecting cancel for symbolId: {}, currentRole: {}", 
                    symbolId, haService.getCurrentRole());
            return new CommandResult(
                    MatchResult.builder().symbolId(symbolId).build(),
                    new SyncPayload(symbolId, Collections.emptyMap(), null, null)
            );
        }

        if (!orderBookService.exists(symbolId)) {
            log.error("OrderBook not initialized for symbolId: {}, system state may be inconsistent. " +
                    "This should not happen as symbols should be loaded during HA activation.", symbolId);
            return new CommandResult(
                    MatchResult.builder().symbolId(symbolId).build(),
                    new SyncPayload(symbolId, Collections.emptyMap(), null, null)
            );
        }

        if (cancelParam.isCancelAll()) {
            return executeCancelAll(cancelParam);
        }
        return executeBatchCancel(cancelParam);
    }

    private CommandResult executeBatchCancel(CancelOrderParam cancelParam) {
        String symbolId = cancelParam.getSymbolId();
        List<String> orderIds = cancelParam.getOrderIds();
        long uid = cancelParam.getUid();

        if (orderIds == null || orderIds.isEmpty()) {
            MatchResult matchResult = MatchResult.builder().symbolId(symbolId).build();
            Map<String, Set<BigDecimal>> emptyLevels = Collections.emptyMap();
            return new CommandResult(matchResult, new SyncPayload(symbolId, emptyLevels, null, null));
        }

        MatchResult matchResult = MatchResult.builder().symbolId(symbolId).build();
        Map<String, Set<BigDecimal>> changedLevels = new HashMap<>();
        List<String> removedOrderIds = new ArrayList<>();

        for (String orderId : orderIds) {
            long id = Long.parseLong(orderId);
            CompactOrderBookEntry compact = orderBookService.getCompactOrder(symbolId, id);
            if (compact == null) {
                log.warn("Order not found for cancel, symbolId: {}, orderId: {}", symbolId, orderId);
                continue;
            }

            // Snapshot before cancel
            long snapshotOrderId = compact.orderId;
            long snapshotAccountId = compact.accountId;
            long snapshotUid = compact.uid;
            long snapshotQuantity = compact.isIceberg ? compact.icebergTotalQuantity : compact.quantity;
            long snapshotRemainingQty = compact.isIceberg ?
                    Math.max(compact.remainingQty, compact.icebergHiddenQuantity) :
                    compact.remainingQty;

            Map<String, Set<Long>> levels = orderBookService.cancelOrder(symbolId, orderId);
            if (!levels.isEmpty()) {
                for (Map.Entry<String, Set<Long>> entry : levels.entrySet()) {
                    Set<BigDecimal> bdPrices = new HashSet<>();
                    for (Long p : entry.getValue()) bdPrices.add(CompactOrderBookEntry.toBigDecimal(p));
                    changedLevels.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).addAll(bdPrices);
                }
            }
            removedOrderIds.add(orderId);

            matchResult.getDealtOrders().add(buildCancelledOrder(
                    snapshotOrderId, snapshotAccountId, snapshotUid, symbolId, snapshotQuantity, snapshotRemainingQty
            ));
        }

        log.info("Batch cancel, symbolId: {}, uid: {}, requested: {}, cancelled: {}",
                symbolId, uid, orderIds.size(), removedOrderIds.size());
        return new CommandResult(matchResult, new SyncPayload(symbolId, changedLevels, null, removedOrderIds));
    }

    private CommandResult executeCancelAll(CancelOrderParam cancelParam) {
        String symbolId = cancelParam.getSymbolId();
        long accountId = cancelParam.getAccountId();
        long uid = cancelParam.getUid();

        CancelAllResult result = orderBookService.cancelAllByAccount(symbolId, accountId);
        MatchResult matchResult = MatchResult.builder().symbolId(symbolId).build();

        for (OrderSnapshot snap : result.snapshots) {
            matchResult.getDealtOrders().add(buildCancelledOrder(
                    snap.orderId, snap.accountId, uid, symbolId, snap.quantity, snap.remainingQty
            ));
        }

        // 转换 Map<String,Set<Long>> -> Map<String,Set<BigDecimal>>
        Map<String, Set<BigDecimal>> changedLevelsBd = new HashMap<>();
        for (Map.Entry<String, Set<Long>> entry : result.changedLevels.entrySet()) {
            Set<BigDecimal> bdPrices = new HashSet<>();
            for (Long p : entry.getValue()) bdPrices.add(CompactOrderBookEntry.toBigDecimal(p));
            changedLevelsBd.put(entry.getKey(), bdPrices);
        }

        log.info("Cancel all, symbolId: {}, uid: {}, accountId: {}, cancelled: {}",
                symbolId, uid, accountId, result.snapshots.size());
        return new CommandResult(matchResult, new SyncPayload(symbolId, changedLevelsBd, null, result.removedOrderIds));
    }

    private MatchOrderResult buildCancelledOrder(long orderId, long accountId, long uid, String symbolId, long quantity, long remainingQty) {
        MatchOrderResult order = new MatchOrderResult();
        order.setOrderId(orderId);
        order.setAccountId(accountId);
        order.setUid(uid);
        order.setSymbolId(symbolId);
        order.setRemainCount(CompactOrderBookEntry.toBigDecimal(remainingQty));
        order.setDealtCount(CompactOrderBookEntry.toBigDecimal(quantity).subtract(CompactOrderBookEntry.toBigDecimal(remainingQty)));
        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdateTime(new java.util.Date());
        return order;
    }
}