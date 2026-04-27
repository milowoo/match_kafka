package com.matching.constant;

public final class CancelReason {

    // 市价单无流动性
    public static final String MARKET_NO_LIQUIDITY = "MARKET_NO_LIQUIDITY";

    // 滑点超限, 剩余数量取消
    public static final String SLIPPAGE_EXCEEDED = "SLIPPAGE_EXCEEDED";

    // 穿越档位超限, 剩余数量取消
    public static final String MAX_LEVELS_EXCEEDED = "MAX_LEVELS_EXCEEDED";

    // IOC订单未完全成交
    public static final String IOC_UNFILLED = "IOC_UNFILLED";

    // FOK订单部分成交 (预检通过但实际撮合未能全部成交)
    public static final String FOK_PARTIAL = "FOK_PARTIAL";

    // FOK订单流动性不足, 无法全部成交
    public static final String FOK_CANNOT_FILL = "FOK_CANNOT_FILL";

    // Post Only订单会立即成交, 被拒绝
    public static final String POST_ONLY_WOULD_MATCH = "POST_ONLY_WOULD_MATCH";

    // 订单簿已满
    public static final String ORDER_BOOK_FULL = "ORDER_BOOK_FULL";

    private CancelReason() {}
}