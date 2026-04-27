package com.matching.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class PriceLevelBookBenchmarkTest {

    private static final int WARMUP_ITERATIONS = 1000;
    private static final int BENCHMARK_ITERATIONS = 10000;
    private static final int PRICE_LEVELS = 100;
    private static final int ORDERS_PER_LEVEL = 50;

    @Test
    @DisplayName("价格查找性能基准测试")
    void benchmarkPriceLookup() {
        PriceLevelBook book = new PriceLevelBook(true);
        populateBook(book);

        long[] searchPrices = new long[BENCHMARK_ITERATIONS];
        Random random = new Random(42);
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            searchPrices[i] = 1000 + random.nextInt(PRICE_LEVELS);
        }

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            book.ordersAtPrice(searchPrices[i % searchPrices.length]);
        }

        long startTime = System.nanoTime();
        for (long price : searchPrices) {
            book.ordersAtPrice(price);
        }
        long totalTime = System.nanoTime() - startTime;

        System.out.printf("跳表查找时间: %.2f ms%n", totalTime / 1_000_000.0);
        System.out.printf("平均每次查找: %.0f ns%n", (double) totalTime / BENCHMARK_ITERATIONS);

        assertTrue(totalTime / BENCHMARK_ITERATIONS < 5000, "平均查找时间应小于5微秒");
    }

    @Test
    @DisplayName("最佳价格获取性能基准测试")
    void benchmarkBestPrice() {
        PriceLevelBook book = new PriceLevelBook(true);
        populateBook(book);

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            book.bestPrice();
        }

        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            book.bestPrice();
        }
        long totalTime = System.nanoTime() - startTime;

        System.out.printf("跳表最佳价格时间: %.2f ms%n", totalTime / 1_000_000.0);
        System.out.printf("平均每次获取: %.0f ns%n", (double) totalTime / BENCHMARK_ITERATIONS);

        assertTrue(totalTime / BENCHMARK_ITERATIONS < 1000, "平均最佳价格获取应小于1微秒");
    }

    @Test
    @DisplayName("深度构建性能基准测试")
    void benchmarkDepthBuilding() {
        PriceLevelBook book = new PriceLevelBook(true);
        populateBook(book);

        final int DEPTH_LEVELS = 20;

        for (int i = 0; i < WARMUP_ITERATIONS / 10; i++) {
            buildDepth(book, DEPTH_LEVELS);
        }

        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS / 10; i++) {
            buildDepth(book, DEPTH_LEVELS);
        }
        long totalTime = System.nanoTime() - startTime;

        System.out.printf("跳表深度构建时间: %.2f ms%n", totalTime / 1_000_000.0);
        System.out.printf("平均每次构建: %.0f μs%n", (double) totalTime / (BENCHMARK_ITERATIONS / 10) / 1000);
    }

    @Test
    @DisplayName("插入删除性能基准测试")
    void benchmarkInsertionDeletion() {
        final int OPERATIONS = 1000;
        Random random = new Random(42);

        CompactOrderBookEntry[] entries = new CompactOrderBookEntry[OPERATIONS];
        for (int i = 0; i < OPERATIONS; i++) {
            CompactOrderBookEntry entry = new CompactOrderBookEntry();
            entry.orderId = i;
            entry.price = 1000 + random.nextInt(100);
            entry.quantity = 10;
            entry.remainingQty = 10;
            entries[i] = entry;
        }

        PriceLevelBook book = new PriceLevelBook(true);
        long startTime = System.nanoTime();
        for (CompactOrderBookEntry entry : entries) {
            book.addOrder(entry);
        }
        long insertTime = System.nanoTime() - startTime;

        startTime = System.nanoTime();
        for (CompactOrderBookEntry entry : entries) {
            book.removeEntry(entry);
        }
        long deleteTime = System.nanoTime() - startTime;

        System.out.printf("跳表插入时间: %.2f ms (平均: %.0f ns/op)%n",
                insertTime / 1_000_000.0, (double) insertTime / OPERATIONS);
        System.out.printf("跳表删除时间: %.2f ms (平均: %.0f ns/op)%n",
                deleteTime / 1_000_000.0, (double) deleteTime / OPERATIONS);

        assertTrue(insertTime / OPERATIONS < 50000, "平均插入时间应小于50微秒");
        assertTrue(deleteTime / OPERATIONS < 50000, "平均删除时间应小于50微秒");
    }

    @Test
    @DisplayName("延迟稳定性测试")
    void benchmarkLatencyStability() {
        PriceLevelBook book = new PriceLevelBook(true);
        final int OPERATIONS = 1000;
        Random random = new Random(42);

        long[] insertLatencies = new long[OPERATIONS];
        long[] lookupLatencies = new long[OPERATIONS];

        for (int i = 0; i < OPERATIONS; i++) {
            CompactOrderBookEntry entry = new CompactOrderBookEntry();
            entry.orderId = i;
            entry.price = 1000 + random.nextInt(100);
            entry.quantity = 10;
            entry.remainingQty = 10;

            long start = System.nanoTime();
            book.addOrder(entry);
            insertLatencies[i] = System.nanoTime() - start;
        }

        for (int i = 0; i < OPERATIONS; i++) {
            long price = 1000 + random.nextInt(100);
            long start = System.nanoTime();
            book.ordersAtPrice(price);
            lookupLatencies[i] = System.nanoTime() - start;
        }

        long insertP50 = percentile(insertLatencies, 50);
        long insertP99 = percentile(insertLatencies, 99);
        long lookupP50 = percentile(lookupLatencies, 50);
        long lookupP99 = percentile(lookupLatencies, 99);

        System.out.printf("插入延迟 - P50: %d ns, P99: %d ns%n", insertP50, insertP99);
        System.out.printf("查找延迟 - P50: %d ns, P99: %d ns%n", lookupP50, lookupP99);

        assertTrue((double) insertP99 / insertP50 < 100, "插入延迟稳定性应该良好");
        assertTrue((double) lookupP99 / lookupP50 < 10, "查找延迟稳定性应该良好");
    }

    private void populateBook(PriceLevelBook book) {
        for (int priceLevel = 0; priceLevel < PRICE_LEVELS; priceLevel++) {
            for (int order = 0; order < ORDERS_PER_LEVEL; order++) {
                CompactOrderBookEntry entry = new CompactOrderBookEntry();
                entry.orderId = priceLevel * ORDERS_PER_LEVEL + order;
                entry.price = 1000 + priceLevel;
                entry.quantity = 10;
                entry.remainingQty = 10;
                book.addOrder(entry);
            }
        }
    }

    private void buildDepth(PriceLevelBook book, int levels) {
        for (int i = 0; i < Math.min(levels, book.size()); i++) {
            book.priceAt(i);
            book.ordersAt(i);
        }
    }

    private long percentile(long[] values, int percentile) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(sorted.length * percentile / 100.0) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
