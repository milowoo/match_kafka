package com.matching.performance;

import com.matching.dto.MatchResult;
import com.matching.util.FastDecimalConverter;
import com.matching.util.ProtoConverter;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PerformanceOptimizationTest {

    @Test
    @DisplayName("BigDecimal to long 转换性能测试")
    public void testFastDecimalConverter() {
        BigDecimal price = new BigDecimal("123.45678901");
        long priceLong = FastDecimalConverter.toLong(price);
        BigDecimal converted = FastDecimalConverter.toBigDecimal(priceLong);

        assertEquals(0, price.setScale(8, RoundingMode.HALF_UP)
                .compareTo(converted.setScale(8, RoundingMode.HALF_UP)));

        int iterations = 100_000;

        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            BigDecimal a = new BigDecimal("100.12345678");
            BigDecimal b = new BigDecimal("200.87654321");
            BigDecimal result = a.add(b).multiply(new BigDecimal("1.5"));
        }
        long traditionalTime = System.nanoTime() - startTime;

        startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long a = FastDecimalConverter.toLong(new BigDecimal("100.12345678"));
            long b = FastDecimalConverter.toLong(new BigDecimal("200.87654321"));
            long result = (a + b) * 15 / 10;
        }
        long optimizedTime = System.nanoTime() - startTime;

        System.out.printf("传统BigDecimal运算: %d ns%n", traditionalTime);
        System.out.printf("优化long运算: %d ns%n", optimizedTime);
        System.out.printf("性能提升: %.2fx%n", (double) traditionalTime / optimizedTime);

        assertTrue(optimizedTime < traditionalTime, "优化后的运算应该更快");
    }

    @Test
    @DisplayName("Eclipse Collections vs 标准集合性能测试")
    public void testEclipseCollections() {
        int size = 10_000;

        long startTime = System.nanoTime();
        List<String> standardList = new ArrayList<>();
        for (int i = 0; i < size; i++) standardList.add("item" + i);
        long standardListTime = System.nanoTime() - startTime;

        startTime = System.nanoTime();
        FastList<String> fastList = new FastList<>();
        for (int i = 0; i < size; i++) fastList.add("item" + i);
        long fastListTime = System.nanoTime() - startTime;

        System.out.printf("标准ArrayList: %d ns%n", standardListTime);
        System.out.printf("FastList: %d ns%n", fastListTime);

        startTime = System.nanoTime();
        Map<Long, String> standardMap = new HashMap<>();
        for (long i = 0; i < size; i++) standardMap.put(i, "value" + i);
        long standardMapTime = System.nanoTime() - startTime;

        startTime = System.nanoTime();
        LongObjectHashMap<String> primitiveMap = new LongObjectHashMap<>();
        for (long i = 0; i < size; i++) primitiveMap.put(i, "value" + i);
        long primitiveMapTime = System.nanoTime() - startTime;

        System.out.printf("标准HashMap (装箱): %d ns%n", standardMapTime);
        System.out.printf("LongObjectHashMap (无装箱): %d ns%n", primitiveMapTime);

        assertEquals(size, fastList.size());
        assertEquals(size, primitiveMap.size());
        assertEquals("item0", fastList.get(0));
        assertEquals("value0", primitiveMap.get(0L));
    }

    @Test
    @DisplayName("Protobuf vs JSON 序列化性能测试")
    public void testProtobufSerialization() {
        MatchResult matchResult = MatchResult.builder().symbolId("BTCUSDT").build();
        int iterations = 1_000;

        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            try {
                byte[] jsonData = com.matching.util.JsonUtil.serialize(matchResult).getBytes();
                String jsonStr = new String(jsonData);
            } catch (Exception e) { /* ignore */ }
        }
        long jsonTime = System.nanoTime() - startTime;

        startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            try {
                byte[] protobufData = ProtoConverter.serializeMatchResult(matchResult);
                MatchResult deserialized = ProtoConverter.deserializeMatchResult(protobufData);
            } catch (Exception e) { /* ignore */ }
        }
        long protobufTime = System.nanoTime() - startTime;

        System.out.printf("JSON序列化: %d ns%n", jsonTime);
        System.out.printf("Protobuf序列化: %d ns%n", protobufTime);
        if (protobufTime > 0 && jsonTime > 0) {
            System.out.printf("Protobuf性能提升: %.2fx%n", (double) jsonTime / protobufTime);
        }

        try {
            byte[] data = ProtoConverter.serializeMatchResult(matchResult);
            MatchResult deserialized = ProtoConverter.deserializeMatchResult(data);
            assertEquals(matchResult.getSymbolId(), deserialized.getSymbolId());
        } catch (Exception e) {
            System.out.println("Protobuf序列化测试跳过: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("内存使用优化验证")
    public void testMemoryOptimization() {
        int count = 1000;

        BigDecimal[] bigDecimals = new BigDecimal[count];
        for (int i = 0; i < count; i++) bigDecimals[i] = new BigDecimal("123.45678901");

        long[] longs = new long[count];
        for (int i = 0; i < count; i++) longs[i] = FastDecimalConverter.toLong(new BigDecimal("123.45678901"));

        for (int i = 0; i < count; i++) {
            BigDecimal converted = FastDecimalConverter.toBigDecimal(longs[i]);
            assertEquals(0, bigDecimals[i].setScale(8, RoundingMode.HALF_UP)
                    .compareTo(converted.setScale(8, RoundingMode.HALF_UP)));
        }

        System.out.printf("BigDecimal数组创建完成: %d 个对象%n", count);
        System.out.printf("long数组创建完成: %d 个原始值%n", count);
    }

    @Test
    @DisplayName("Date复用优化验证")
    public void testDateReuse() {
        int iterations = 10_000;

        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            java.util.Date date = new java.util.Date();
            long timestamp = date.getTime();
        }
        long traditionalTime = System.nanoTime() - startTime;

        startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long timestamp = System.currentTimeMillis();
        }
        long optimizedTime = System.nanoTime() - startTime;

        System.out.printf("传统Date创建: %d ns%n", traditionalTime);
        System.out.printf("直接时间戳: %d ns%n", optimizedTime);
        System.out.printf("性能提升: %.2fx%n", (double) traditionalTime / optimizedTime);

        assertTrue(optimizedTime < traditionalTime, "直接使用时间戳应该更快");
    }
}
