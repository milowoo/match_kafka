package com.matching.command;

import com.matching.dto.PlaceMiniOrderParam;
import com.matching.dto.PlaceOrderParam;
import com.matching.engine.CompactOrderBookEntry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 构建 taker entry，含冰山单初始化。
 */
@Component
public class TakerOrderFactory {

    public CompactOrderBookEntry create(MatchContext ctx) {
        PlaceOrderParam message = ctx.message;
        PlaceMiniOrderParam miniOrder = ctx.miniOrder;

        CompactOrderBookEntry taker = ctx.matchEngine.acquireEntry();
        taker.orderId = miniOrder.getOrderNo();
        taker.accountId = message.getAccountId();
        taker.uid = message.getUid();
        taker.price = ctx.isMarket() ? 0 : CompactOrderBookEntry.toLong(miniOrder.getDelegatePrice());
        taker.quantity = CompactOrderBookEntry.toLong(
                miniOrder.getDelegateCount() != null ? miniOrder.getDelegateCount() : BigDecimal.ZERO);
        taker.remainingQty = taker.quantity;
        taker.requestTime = message.getRequestTime();
        taker.side = ctx.side;
        taker.vip = miniOrder.isVip();

        if (miniOrder.isIceberg()) {
            taker.isIceberg = true;
            taker.icebergTotalQuantity = CompactOrderBookEntry.toLong(miniOrder.getIcebergTotalQuantity());
            taker.icebergRefreshQuantity = CompactOrderBookEntry.toLong(miniOrder.getIcebergRefreshQuantity());
            long initialDisplayQty = Math.min(taker.icebergRefreshQuantity, taker.quantity);
            taker.icebergDisplayQuantity = initialDisplayQty;
            taker.icebergHiddenQuantity = taker.quantity - initialDisplayQty;
            taker.remainingQty = initialDisplayQty;
        }

        return taker;
    }
}