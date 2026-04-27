package com.matching.performance;

import com.matching.engine.CompactOrderBookEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;

/**
 * 数据类型性能对比测试 对比 String vs BigDecimal vs long 在高频交易场景下的性能
 */
public class DataTypePerformanceTest {

    private static final int ITERATIONS = 1_000_000;
    private static final String[] TEST_PRICES = {
            "123.45678901", "0.00000001", "99999.99999999",
            "1.23456789", "0.12345678", "12345.67890123"
    };

    @Test
    @DisplayName("String解析性能测试")
    void benchmarkStringParsing() {
        System.out.println("=== String解析性能测试 ===");

        // 方案1: String → BigDecimal → long
        long startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            String priceStr = TEST_PRICES[i % TEST_PRICES.length];
            BigDecimal bd = new BigDecimal(priceStr);
            long longValue = CompactOrderBookEntry.toLong(bd);
        }
        long bigDecimalTime = System.nanoTime() - startTime;

        // 方案2: String → long（直接解析）
        startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            String priceStr = TEST_PRICES[i % TEST_PRICES.length];
            long longValue = parseStringToLong(priceStr);
        }
        long directParseTime = System.nanoTime() - startTime;

        // 方案3: String → double → long
        startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            String priceStr = TEST_PRICES[i % TEST_PRICES.length];
            double doubleValue = Double.parseDouble(priceStr);
            long longValue = (long) (doubleValue * CompactOrderBookEntry.PRECISION);
        }
        long doubleParseTime = System.nanoTime() - startTime;

        System.out.printf("BigDecimal解析: %.2f ms (%.0f ns/op)%n",
                bigDecimalTime / 1_000_000.0, (double) bigDecimalTime / ITERATIONS);
        System.out.printf("直接String解析: %.2f ms (%.0f ns/op)%n",
                directParseTime / 1_000_000.0, (double) directParseTime / ITERATIONS);
        System.out.printf("Double解析: %.2f ms (%.0f ns/op)%n",
                doubleParseTime / 1_000_000.0, (double) doubleParseTime / ITERATIONS);

        System.out.printf("直接解析性能提升: %.2fx%n", (double) bigDecimalTime / directParseTime);
        System.out.printf("Double解析性能提升: %.2fx%n", (double) bigDecimalTime / doubleParseTime);
    }

    @Test
    @DisplayName("数值格式化性能测试")
    void benchmarkNumberFormatting() {
        System.out.println("=== 数值格式化性能测试 ===");

        long[] testValues = {123456789011L, 999999999999L, 1234567891, 12345678L, 1234567890123L};

        // 方案1: long → BigDecimal → String
        long startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            long value = testValues[i % testValues.length];
            BigDecimal bd = CompactOrderBookEntry.toBigDecimal(value);
            String result = bd.toPlainString();
        }
        long bigDecimalTime = System.nanoTime() - startTime;

        // 方案2: long → String（直接格式化）
        startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            long value = testValues[i % testValues.length];
            String result = formatLongToString(value);
        }
        long directFormatTime = System.nanoTime() - startTime;

        System.out.printf("BigDecimal格式化: %.2f ms (%.0f ns/op)%n",
                bigDecimalTime / 1_000_000.0, (double) bigDecimalTime / ITERATIONS);
        System.out.printf("直接String格式化: %.2f ms (%.0f ns/op)%n",
                directFormatTime / 1_000_000.0, (double) directFormatTime / ITERATIONS);

        System.out.printf("直接格式化性能提升: %.2fx%n", (double) bigDecimalTime / directFormatTime);
    }

    /**
     * 直接解析String到long，避免BigDecimal开销
     */
    private long parseStringToLong(String str) {
        if (str == null || str.isEmpty()) return 0;

        int dotIndex = str.indexOf('.');
        if (dotIndex == -1) {
            // 整数
            return Long.parseLong(str) * CompactOrderBookEntry.PRECISION;
        }

        // 小数
        String intPart = str.substring(0, dotIndex);
        String fracPart = str.substring(dotIndex + 1);

        // 补齐或截断到8位小数
        if (fracPart.length() > 8) {
            fracPart = fracPart.substring(0, 8);
        } else {
            while (fracPart.length() < 8) {
                fracPart += "0";
            }
        }

        long intValue = intPart.isEmpty() ? 0 : Long.parseLong(intPart);
        long fracValue = Long.parseLong(fracPart);

        return intValue * CompactOrderBookEntry.PRECISION + fracValue;
    }

    /**
     * 直接格式化long到String，避免BigDecimal开销
     */
    private String formatLongToString(long value) {
        if (value == 0) return "0";

        long intPart = value / CompactOrderBookEntry.PRECISION;
        long fracPart = value % CompactOrderBookEntry.PRECISION;

        if (fracPart == 0) {
            return Long.toString(intPart);
        }

        // 移除尾随零
        String fracStr = String.format("%08d", fracPart);
        while (fracStr.length() > 1 && fracStr.endsWith("0")) {
            fracStr = fracStr.substring(0, fracStr.length() - 1);
        }

        return intPart + "." + fracStr;
    }
}