package com.matching.command;

import com.matching.constant.OrderStatus;
import com.matching.constant.TakerMakerFlag;
import com.matching.dto.*;
import com.matching.engine.CompactOrderBookEntry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Date;

/**
 * 构建撮合结果：成交记录、订单状态、拒绝结果、createOrder。
 */
@Component
public class MatchResultBuilder {

    public MatchCreateOrderResult buildCreateOrder(PlaceOrderParam message, PlaceMiniOrderParam miniOrder) {
        MatchCreateOrderResult co = new MatchCreateOrderResult();
        co.setId(miniOrder.getOrderNo());
        co.setAccountId(message.getAccountId());
        co.setUid(message.getUid());
        co.setSecondBusinessLine(message.getSecondBusinessLine());
        co.setTokenId(message.getTokenId());
        co.setSymbolId(message.getSymbolId());
        co.setDelegateType(miniOrder.getDelegateType());
        co.setOrderType(miniOrder.getOrderType());
        co.setControlValue(miniOrder.getControlValue());
        co.setDelegateCount(miniOrder.getDelegateCount());
        co.setDelegateAmount(miniOrder.getDelegateAmount());
        co.setDelegatePrice(miniOrder.getDelegatePrice());
        co.setDealtCount(BigDecimal.ZERO);
        co.setDealtAmount(BigDecimal.ZERO);
        co.setAverageDealPrice(BigDecimal.ZERO);
        co.setStatus(OrderStatus.NEW);
        co.setBusinessSource(miniOrder.getBusinessSource());
        co.setEnterPointSource(miniOrder.getEnterPointSource());
        co.setCreateTime(miniOrder.getCreateTime());
        co.setUpdateTime(new Date());
        co.setParams(miniOrder.getParams());
        co.setVersion(0L);
        return co;
    }

    public CommandResult buildRejectedResult(PlaceOrderParam message, String reason) {
        String symbolId = message.getSymbolId();
        PlaceMiniOrderParam miniOrder = message.getOrderList().get(0);
        MatchResult matchResult = MatchResult.builder().symbolId(symbolId).build();

        MatchCreateOrderResult co = buildCreateOrder(message, miniOrder);
        co.setStatus(OrderStatus.REJECTED);
        co.setCancelReason(reason);
        matchResult.getCreateOrders().add(co);

        return new CommandResult(matchResult, new SyncPayload(symbolId, Collections.emptyMap(), null, null));
    }

    public void updateCreateOrderStatus(MatchCreateOrderResult createOrder, CompactOrderBookEntry entry) {
        if (entry.remainingQty == entry.quantity) {
            createOrder.setStatus(OrderStatus.PENDING);
        } else {
            createOrder.setStatus(OrderStatus.PARTIAL_FILLED);
            createOrder.setDealtCount(CompactOrderBookEntry.toBigDecimal(entry.quantity - entry.remainingQty));
        }
    }

    /**
     * 更新 createOrder 的成交汇总（均价、总额）。
     */
    public void updateCreateOrderDealtSummary(MatchCreateOrderResult createOrder,
                                              BigDecimal totalDealtAmount, BigDecimal totalDealtCount) {
        if (totalDealtCount.compareTo(BigDecimal.ZERO) > 0) {
            createOrder.setDealtAmount(totalDealtAmount);
            try {
                createOrder.setAverageDealPrice(totalDealtAmount.divide(totalDealtCount, 8, RoundingMode.HALF_UP));
            } catch (ArithmeticException e) {
                createOrder.setAverageDealPrice(BigDecimal.ZERO);
            }
        }
    }

    public void buildDealtRecords(MatchContext ctx, CompactOrderBookEntry maker,
                                  long priceLong, long fillQty, long fillAmount,
                                  long takerRecordId, long makerRecordId, Date now) {
        // 转换为BigDecimal用于输出
        BigDecimal price = CompactOrderBookEntry.toBigDecimal(priceLong);
        BigDecimal fillQtyBd = CompactOrderBookEntry.toBigDecimal(fillQty);
        BigDecimal fillAmountBd = CompactOrderBookEntry.toBigDecimal(fillAmount);
        PlaceOrderParam message = ctx.message;
        PlaceMiniOrderParam miniOrder = ctx.miniOrder;

        MatchDealtResult takerDealt = new MatchDealtResult();
        takerDealt.setRecordId(takerRecordId);
        takerDealt.setOrderId(miniOrder.getOrderNo());
        takerDealt.setBusinessLine(message.getBusinessLine());
        takerDealt.setSecondBusinessLine(message.getSecondBusinessLine());
        takerDealt.setAccountId(message.getAccountId());
        takerDealt.setTokenId(message.getTokenId());
        takerDealt.setSymbolId(ctx.symbolId);
        takerDealt.setDelegateType(miniOrder.getDelegateType());
        takerDealt.setOrderType(miniOrder.getOrderType());
        takerDealt.setDealtCount(fillQtyBd);
        takerDealt.setDealtAmount(fillAmountBd);
        takerDealt.setDealtPrice(price);
        takerDealt.setTakerMakerFlag(TakerMakerFlag.TAKER);
        takerDealt.setTargetAccountId(maker.accountId);
        takerDealt.setTargetRecordId(makerRecordId);
        takerDealt.setCreateTime(now);
        ctx.matchResult.getDealtRecords().add(takerDealt);

        MatchDealtResult makerDealt = new MatchDealtResult();
        makerDealt.setRecordId(makerRecordId);
        makerDealt.setOrderId(maker.orderId);
        makerDealt.setBusinessLine(message.getBusinessLine());
        makerDealt.setSecondBusinessLine(message.getSecondBusinessLine());
        makerDealt.setAccountId(maker.accountId);
        makerDealt.setTokenId(message.getTokenId());
        makerDealt.setSymbolId(ctx.symbolId);
        makerDealt.setDealtCount(fillQtyBd);
        makerDealt.setDealtAmount(fillAmountBd);
        makerDealt.setDealtPrice(price);
        makerDealt.setTakerMakerFlag(TakerMakerFlag.MAKER);
        makerDealt.setTargetAccountId(takerDealt.getAccountId());
        makerDealt.setTargetRecordId(takerRecordId);
        makerDealt.setCreateTime(now);
        ctx.matchResult.getDealtRecords().add(makerDealt);
    }

    public void buildOrderResults(MatchContext ctx, CompactOrderBookEntry maker,
                                  long fillQty, Date now) {
        // 转换为BigDecimal用于输出
        BigDecimal fillQtyBd = CompactOrderBookEntry.toBigDecimal(fillQty);
        PlaceOrderParam message = ctx.message;
        PlaceMiniOrderParam miniOrder = ctx.miniOrder;
        CompactOrderBookEntry taker = ctx.taker;

        MatchOrderResult takerResult = new MatchOrderResult();
        takerResult.setOrderId(miniOrder.getOrderNo());
        takerResult.setAccountId(message.getAccountId());
        takerResult.setBusinessLine(message.getBusinessLine());
        takerResult.setSecondBusinessLine(message.getSecondBusinessLine());
        takerResult.setTokenId(message.getTokenId());
        takerResult.setSymbolId(ctx.symbolId);
        takerResult.setDealtCount(fillQtyBd);
        takerResult.setRemainCount(CompactOrderBookEntry.toBigDecimal(taker.remainingQty));
        takerResult.setStatus(taker.remainingQty == 0 ? OrderStatus.FILLED : OrderStatus.PARTIAL_FILLED);
        takerResult.setUpdateTime(now);
        takerResult.setControlValue(miniOrder.getControlValue());
        ctx.matchResult.getDealtOrders().add(takerResult);

        MatchOrderResult makerResult = new MatchOrderResult();
        makerResult.setOrderId(maker.orderId);
        makerResult.setAccountId(maker.accountId);
        makerResult.setBusinessLine(message.getBusinessLine());
        makerResult.setSecondBusinessLine(message.getSecondBusinessLine());
        makerResult.setTokenId(message.getTokenId());
        makerResult.setSymbolId(ctx.symbolId);
        makerResult.setDealtCount(fillQtyBd);
        makerResult.setRemainCount(CompactOrderBookEntry.toBigDecimal(maker.remainingQty));
        makerResult.setStatus(maker.remainingQty == 0 ? OrderStatus.FILLED : OrderStatus.PARTIAL_FILLED);
        makerResult.setUpdateTime(now);
        ctx.matchResult.getDealtOrders().add(makerResult);
    }
}