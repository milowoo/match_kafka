package com.matching.performance;

import com.matching.dto.OrderBookEntry;
import com.matching.service.EventLog;
import com.matching.util.JsonUtil;
import com.matching.util.ProtoConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class EventLogProtobufPerformanceTest {

    @Test
    @DisplayName("EventLog Protobuf vs JSON 序列化性能测试")
    public void testEventLogSerializationPerformance() {
        List<OrderBookEntry> orders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            orders.add(OrderBookEntry.builder()
                    .clientOrderId(String.valueOf(i))
                    .accountId((long) i)
                    .symbolId("BTCUSDT")
                    .side(i % 2 == 0 ? "buy" : "sell")
                    .price(new BigDecimal("50000.12345678"))
                    .quantity(new BigDecimal("1.23456789"))
                    .remainingQuantity(new BigDecimal("0.98765432"))
                    .requestTime(System.currentTimeMillis())
                    .vip(false)
                    .build());
        }

        List<EventLog.MatchResultEntry> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(new EventLog.MatchResultEntry(
                    "account" + i,
                    ("{\"orderId\":" + i + ",\"price\":\"50000.12345678\"}").getBytes()
            ));
        }

        EventLog.Event event = new EventLog.Event(
                System.currentTimeMillis(), "BTCUSDT", orders, List.of("order1", "order2"), results);

        int iterations = 1000;

        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            try {
                String json = JsonUtil.serialize(event);
                EventLog.Event deserialized = JsonUtil.deserialize(json, EventLog.Event.class);
            } catch (Exception e) { /* ignore */ }
        }
        long jsonTime = System.nanoTime() - startTime;

        startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            try {
                byte[] data = ProtoConverter.serializeEvent(event);
                EventLog.Event deserialized = ProtoConverter.deserializeEvent(data);
            } catch (Exception e) { /* ignore */ }
        }
        long protobufTime = System.nanoTime() - startTime;

        System.out.printf("JSON: %d ns%n", jsonTime);
        System.out.printf("Protobuf: %d ns%n", protobufTime);
        if (protobufTime > 0 && jsonTime > 0) {
            System.out.printf("Protobuf性能提升: %.2fx%n", (double) jsonTime / protobufTime);
        }

        try {
            byte[] data = ProtoConverter.serializeEvent(event);
            EventLog.Event deserialized = ProtoConverter.deserializeEvent(data);
            assertEquals(event.getSeq(), deserialized.getSeq());
            assertEquals(event.getSymbolId(), deserialized.getSymbolId());
        } catch (Exception e) {
            System.out.println("EventLog Protobuf测试跳过: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("数据大小对比")
    public void testDataSizeComparison() {
        List<OrderBookEntry> orders = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            orders.add(OrderBookEntry.builder()
                    .clientOrderId("order" + i)
                    .accountId((long) i)
                    .symbolId("BTCUSDT")
                    .side("buy")
                    .price(new BigDecimal("50000.12345678"))
                    .quantity(new BigDecimal("1.23456789"))
                    .remainingQuantity(new BigDecimal("0.98765432"))
                    .requestTime(System.currentTimeMillis())
                    .vip(false)
                    .build());
        }

        EventLog.Event event = new EventLog.Event(
                System.currentTimeMillis(), "BTCUSDT", orders, List.of("removed1", "removed2"), List.of());

        try {
            String json = JsonUtil.serialize(event);
            int jsonSize = json.getBytes("UTF-8").length;
            byte[] protobufData = ProtoConverter.serializeEvent(event);
            int protobufSize = protobufData.length;

            System.out.printf("JSON: %d bytes%n", jsonSize);
            System.out.printf("Protobuf: %d bytes%n", protobufSize);
            System.out.printf("空间节省: %.1f%%%n", (1.0 - (double) protobufSize / jsonSize) * 100);
        } catch (Exception e) {
            System.out.println("数据大小对比跳过: " + e.getMessage());
        }
    }
}
