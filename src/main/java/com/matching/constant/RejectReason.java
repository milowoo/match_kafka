package com.matching.constant;

public final class RejectReason {

    // 交易对配置
    public static final String SYMBOL_NOT_CONFIGURED = "SYMBOL_NOT_CONFIGURED";
    public static final String TRADING_DISABLED = "TRADING_DISABLED";

    // 订单参数
    public static final String EMPTY_ORDER_LIST = "EMPTY_ORDER_LIST";
    public static final String QUANTITY_NOT_POSITIVE = "QUANTITY_NOT_POSITIVE";
    public static final String QUANTITY_BELOW_MIN = "QUANTITY_BELOW_MIN";
    public static final String QUANTITY_EXCEED_MAX = "QUANTITY_EXCEED_MAX";
    public static final String PRICE_NOT_POSITIVE = "PRICE_NOT_POSITIVE";
    public static final String PRICE_BELOW_MIN = "PRICE_BELOW_MIN";
    public static final String PRICE_EXCEED_MAX = "PRICE_EXCEED_MAX";
    public static final String PRICE_PRECISION_EXCEED = "PRICE_PRECISION_EXCEED";
    public static final String QUANTITY_PRECISION_EXCEED = "QUANTITY_PRECISION_EXCEED";
    public static final String NOTIONAL_BELOW_MIN = "NOTIONAL_BELOW_MIN";
    public static final String PRICE_DEVIATION_EXCEED = "PRICE_DEVIATION_EXCEED";

    // 账户限制
    public static final String MAX_OPEN_ORDERS_REACHED = "MAX_OPEN_ORDERS_REACHED";
    public static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";

    // 冰山单相关
    public static final String ICEBERG_TOTAL_QUANTITY_INVALID = "ICEBERG_TOTAL_QUANTITY_INVALID";
    public static final String ICEBERG_DISPLAY_QUANTITY_INVALID = "ICEBERG_DISPLAY_QUANTITY_INVALID";
    public static final String ICEBERG_REFRESH_QUANTITY_INVALID = "ICEBERG_REFRESH_QUANTITY_INVALID";
    public static final String ICEBERG_DISPLAY_EXCEED_TOTAL = "ICEBERG_DISPLAY_EXCEED_TOTAL";
    public static final String ICEBERG_REFRESH_EXCEED_TOTAL = "ICEBERG_REFRESH_EXCEED_TOTAL";
    public static final String ICEBERG_TOTAL_QUANTITY_MISMATCH = "ICEBERG_TOTAL_QUANTITY_MISMATCH";
    public static final String ICEBERG_DISPLAY_BELOW_MIN = "ICEBERG_DISPLAY_BELOW_MIN";

    // uid
    public static final String UID_REQUIRED = "UID_REQUIRED";

    private RejectReason() {}
}