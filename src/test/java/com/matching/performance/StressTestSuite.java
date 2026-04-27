package com.matching.performance;

import com.matching.engine.CompactOrderBookEntry;
import com.matching.engine.MatchEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class StressTestSuite {

    private static final int WARM_UP_ITERATIONS = 10000;
    private static final Random RANDOM = new Random(42);

    @BeforeEach
    void setUp() {
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Test
    @DisplayName("单交易对极限TPS测试")
    void testSingleSymbolMaxTPS() {
        MatchEngine engine = new MatchEngine("BTCUSDT", 100000);
        final int TEST_ORDERS = 100000;

        warmUpEngine(engine, WARM_UP_ITERATIONS);
        List<CompactOrderBookEntry> orders = generateRealisticOrders(TEST_ORDERS, "BTCUSDT");

        long startTime = System.nanoTime();
        int processed = 0;
        for (CompactOrderBookEntry order : orders) {
            if (engine.addOrder(order)) processed++;
            if (processed % 100 == 0) simulateMatching(engine);
        }
        long totalTime = System.nanoTime() - startTime;
        double tps = (double) processed / (totalTime / 1_000_000_000.0);

        System.out.printf("处理订单数: %d%n", processed);
        System.out.printf("总耗时: %.2f ms%n", totalTime / 1_000_000.0);
        System.out.printf("平均TPS: %.0f 订单/秒%n", tps);
        System.out.printf("平均延迟: %.2f us/订单%n", (double) totalTime / processed / 1000);
    }

    @Test
    @DisplayName("多交易对并发测试")
    void testMultiSymbolConcurrency() throws InterruptedException {
        final int SYMBOL_COUNT = 50;
        final int ORDERS_PER_SYMBOL = 10000;
        final int THREAD_COUNT = 10;

        Map<String, MatchEngine> engines = new ConcurrentHashMap<>();
        for (int i = 0; i < SYMBOL_COUNT; i++) {
            String symbol = "SYMBOL_" + i;
            engines.put(symbol, new MatchEngine(symbol, 50000));
        }

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(SYMBOL_COUNT);
        AtomicLong totalProcessed = new AtomicLong(0);
        AtomicLong totalTime = new AtomicLong(0);
        long globalStartTime = System.nanoTime();

        for (int i = 0; i < SYMBOL_COUNT; i++) {
            final String symbol = "SYMBOL_" + i;
            final MatchEngine engine = engines.get(symbol);
            executor.submit(() -> {
                try {
                    List<CompactOrderBookEntry> orders = generateRealisticOrders(ORDERS_PER_SYMBOL, symbol);
                    long startTime = System.nanoTime();
                    int processed = 0;
                    for (CompactOrderBookEntry order : orders) {
                        if (engine.addOrder(order)) processed++;
                        if (processed % 200 == 0) simulateMatching(engine);
                    }
                    totalProcessed.addAndGet(processed);
                    totalTime.addAndGet(System.nanoTime() - startTime);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        long globalEndTime = System.nanoTime();
        executor.shutdown();

        double globalTps = (double) totalProcessed.get() / ((globalEndTime - globalStartTime) / 1_000_000_000.0);
        System.out.printf("交易对数量: %d%n", SYMBOL_COUNT);
        System.out.printf("总处理订单: %d%n", totalProcessed.get());
        System.out.printf("全局TPS: %.0f 订单/秒%n", globalTps);
    }

    @Test
    @DisplayName("内存压力测试")
    void testMemoryStress() {
        Runtime runtime = Runtime.getRuntime();
        MatchEngine engine = new MatchEngine("MEMTEST", 500000);

        runtime.gc();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        final int LARGE_ORDER_COUNT = 200000;
        List<CompactOrderBookEntry> orders = generateRealisticOrders(LARGE_ORDER_COUNT, "MEMTEST");

        long startTime = System.nanoTime();
        int processed = 0;
        for (int batch = 0; batch < 10; batch++) {
            int batchStart = batch * (LARGE_ORDER_COUNT / 10);
            int batchEnd = (batch + 1) * (LARGE_ORDER_COUNT / 10);
            for (int i = batchStart; i < batchEnd; i++) {
                if (engine.addOrder(orders.get(i))) processed++;
            }
            runtime.gc();
            long memoryUsed = (runtime.totalMemory() - runtime.freeMemory()) - initialMemory;
            System.out.printf("批次 %d: 处理 %d 订单, 内存使用 %.1f MB%n",
                    batch + 1, processed, memoryUsed / 1024.0 / 1024.0);
        }

        long totalTime = System.nanoTime() - startTime;
        runtime.gc();
        long totalMemoryUsed = (runtime.totalMemory() - runtime.freeMemory()) - initialMemory;
        System.out.printf("总处理订单: %d, 总内存: %.1f MB%n", processed, totalMemoryUsed / 1024.0 / 1024.0);
    }

    @Test
    @DisplayName("延迟分布测试")
    void testLatencyDistribution() {
        MatchEngine engine = new MatchEngine("LATENCY", 100000);
        final int TEST_COUNT = 50000;

        warmUpEngine(engine, WARM_UP_ITERATIONS);
        List<CompactOrderBookEntry> orders = generateRealisticOrders(TEST_COUNT, "LATENCY");
        long[] latencies = new long[TEST_COUNT];

        for (int i = 0; i < TEST_COUNT; i++) {
            long startTime = System.nanoTime();
            engine.addOrder(orders.get(i));
            latencies[i] = System.nanoTime() - startTime;
            if (i % 500 == 0) simulateMatching(engine);
        }

        Arrays.sort(latencies);
        long p50 = latencies[TEST_COUNT / 2];
        long p99 = latencies[(int) (TEST_COUNT * 0.99)];
        double avg = Arrays.stream(latencies).average().orElse(0);

        System.out.printf("延迟统计: avg=%.0f ns, P50=%d ns, P99=%d ns%n", avg, p50, p99);
    }

    @Test
    @DisplayName("订单簿深度压力测试")
    void testOrderBookDepthStress() {
        MatchEngine engine = new MatchEngine("DEPTH", 200000);
        final int PRICE_LEVELS = 1000;
        final int ORDERS_PER_LEVEL = 100;

        long startTime = System.nanoTime();
        int totalOrders = 0;

        for (int level = 0; level < PRICE_LEVELS; level++) {
            BigDecimal basePrice = new BigDecimal("50000.00000000");
            BigDecimal buyPrice = basePrice.subtract(new BigDecimal(level));
            for (int i = 0; i < ORDERS_PER_LEVEL; i++) {
                engine.addOrder(createOrder(totalOrders++, 1000 + i, buyPrice,
                        new BigDecimal("1.00000000"), CompactOrderBookEntry.BUY));
            }
            BigDecimal sellPrice = basePrice.add(new BigDecimal(level + 1));
            for (int i = 0; i < ORDERS_PER_LEVEL; i++) {
                engine.addOrder(createOrder(totalOrders++, 2000 + i, sellPrice,
                        new BigDecimal("1.00000000"), CompactOrderBookEntry.SELL));
            }
        }

        long buildTime = System.nanoTime() - startTime;
        System.out.printf("订单簿构建: %d 订单, %.2f ms%n", totalOrders, buildTime / 1_000_000.0);
        System.out.printf("买盘 %d 层, 卖盘 %d 层%n", engine.getBuyBook().size(), engine.getSellBook().size());
    }

    // ====================== 辅助方法 ======================

    private List<CompactOrderBookEntry> generateRealisticOrders(int count, String symbol) {
        List<CompactOrderBookEntry> orders = new ArrayList<>(count);
        BigDecimal basePrice = new BigDecimal("50000.00000000");

        for (int i = 0; i < count; i++) {
            double priceVariation = RANDOM.nextGaussian() * 0.02;
            BigDecimal price = basePrice.multiply(BigDecimal.valueOf(1 + priceVariation));
            double quantity = Math.max(0.01, Math.min(10.0, Math.exp(RANDOM.nextGaussian() * 1.5 + 0.5)));
            byte side = RANDOM.nextDouble() < 0.52 ? CompactOrderBookEntry.BUY : CompactOrderBookEntry.SELL;

            CompactOrderBookEntry order = createOrder(i, 1000 + RANDOM.nextInt(10000),
                    price, BigDecimal.valueOf(quantity), side);
            order.vip = RANDOM.nextDouble() < 0.05;
            orders.add(order);
        }
        return orders;
    }

    private CompactOrderBookEntry createOrder(long orderId, long accountId,
                                              BigDecimal price, BigDecimal quantity, byte side) {
        CompactOrderBookEntry order = new CompactOrderBookEntry();
        order.orderId = orderId;
        order.accountId = accountId;
        order.price = CompactOrderBookEntry.toLong(price);
        order.quantity = CompactOrderBookEntry.toLong(quantity);
        order.remainingQty = order.quantity;
        order.side = side;
        order.requestTime = System.currentTimeMillis();
        return order;
    }

    private void warmUpEngine(MatchEngine engine, int iterations) {
        List<CompactOrderBookEntry> warmUpOrders = generateRealisticOrders(iterations, "WARMUP");
        for (CompactOrderBookEntry order : warmUpOrders) engine.addOrder(order);
        for (CompactOrderBookEntry order : warmUpOrders) engine.cancelOrder(order.orderId);
    }

    private void simulateMatching(MatchEngine engine) {
        // 简单模拟，不做实际撮合
    }
}
