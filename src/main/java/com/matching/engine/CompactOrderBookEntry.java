package com.matching.engine;

import com.matching.dto.OrderBookEntry;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class CompactOrderBookEntry {
    public static final long PRECISION = 100_000_000L;
    public static final int SCALE = 8;

    public static final byte BUY = 0;
    public static final byte SELL = 1;

    public long orderId;
    public long accountId;
    public long uid;
    public long price;        // long representation: actual_price * 10^8
    public long quantity;     // long representation: actual_qty * 10^8
    public long remainingQty;
    public long requestTime;
    public byte side;
    public boolean vip;

    // 冰山单相关字段
    public boolean isIceberg;
    public long icebergTotalQuantity;    // 冰山单总量
    public long icebergDisplayQuantity;  // 当前显示量
    public long icebergHiddenQuantity;   // 隐藏量
    public long icebergRefreshQuantity;  // 每次刷新量

    // Intrusive linked list pointers (used by OrderList)
    public CompactOrderBookEntry prev;
    public CompactOrderBookEntry next;

    public void reset() {
        orderId = 0;
        accountId = 0;
        uid = 0;
        price = 0;
        quantity = 0;
        remainingQty = 0;
        requestTime = 0;
        side = 0;
        vip = false;
        // 重置冰山单字段
        isIceberg = false;
        icebergTotalQuantity = 0;
        icebergDisplayQuantity = 0;
        icebergHiddenQuantity = 0;
        icebergRefreshQuantity = 0;
        prev = null;
        next = null;
    }

    public boolean isFilled() {
        return remainingQty <= 0;
    }

    public static long toLong(BigDecimal value) {
        if (value == null) return 0;
        try {
            // 使用HALF_UP舍入模式确保金融计算的准确性
            return value.setScale(SCALE, RoundingMode.HALF_UP).movePointRight(SCALE).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Value overflow: " + value.toPlainString(), e);
        }
    }

    public static BigDecimal toBigDecimal(long value) {
        return BigDecimal.valueOf(value, SCALE);
    }

    public static CompactOrderBookEntry fromEntry(OrderBookEntry entry) {
        CompactOrderBookEntry compact = new CompactOrderBookEntry();
        compact.orderId = Long.parseLong(entry.getClientOrderId());
        compact.accountId = entry.getAccountId();
        compact.uid = entry.getUid();
        compact.price = toLong(entry.getPrice());
        compact.quantity = toLong(entry.getQuantity());
        compact.remainingQty = toLong(entry.getRemainingQuantity());
        compact.requestTime = entry.getRequestTime();
        compact.side = "buy".equalsIgnoreCase(entry.getSide()) ? BUY : SELL;
        compact.vip = entry.isVip();

        // 设置冰山单字段
        if (compact.isIceberg = entry.isIceberg()) {
            compact.icebergTotalQuantity = toLong(entry.getIcebergTotalQuantity());
            compact.icebergDisplayQuantity = toLong(entry.getIcebergDisplayQuantity());
            compact.icebergHiddenQuantity = toLong(entry.getIcebergHiddenQuantity());
            compact.icebergRefreshQuantity = toLong(entry.getIcebergRefreshQuantity());
        }

        return compact;
    }

    public OrderBookEntry toEntry(String symbolId) {
        OrderBookEntry entry = new OrderBookEntry();
        entry.setClientOrderId(String.valueOf(orderId));
        entry.setAccountId(accountId);
        entry.setUid(uid);
        entry.setSymbolId(symbolId);
        entry.setSide(side == BUY ? "buy" : "sell");
        entry.setPrice(toBigDecimal(price));
        entry.setQuantity(toBigDecimal(quantity));
        entry.setRemainingQuantity(toBigDecimal(remainingQty));
        entry.setRequestTime(requestTime);
        entry.setVip(vip);

        // 设置冰山单字段
        if (isIceberg) {
            entry.setIceberg(true);
            entry.setIcebergTotalQuantity(toBigDecimal(icebergTotalQuantity));
            entry.setIcebergDisplayQuantity(toBigDecimal(icebergDisplayQuantity));
            entry.setIcebergHiddenQuantity(toBigDecimal(icebergHiddenQuantity));
            entry.setIcebergRefreshQuantity(toBigDecimal(icebergRefreshQuantity));
        }

        return entry;
    }
}