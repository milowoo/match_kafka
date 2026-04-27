package com.matching.service;

import org.slf4j.MDC;
import java.util.UUID;

public class TraceContext {

    private static final String TRACE_ID = "traceId";
    private static final String ORDER_ID = "orderId";
    private static final String SYMBOL_ID = "symbolId";

    public static void start(String symbolId, String orderId) {
        MDC.put(TRACE_ID, generateTraceId());
        MDC.put(SYMBOL_ID, symbolId);
        if (orderId != null) MDC.put(ORDER_ID, orderId);
    }

    public static void start(String traceId, String symbolId, String orderId) {
        MDC.put(TRACE_ID, traceId != null ? traceId : generateTraceId());
        MDC.put(SYMBOL_ID, symbolId);
        if (orderId != null) MDC.put(ORDER_ID, orderId);
    }

    public static String getTraceId() {
        return MDC.get(TRACE_ID);
    }

    public static void clear() {
        MDC.clear();
    }

    private static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}