package com.matching.engine;

import com.matching.dto.OrderBookEntry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MatchEngineTest {

    private MatchEngine engine;
    private static final String TEST_SYMBOL = "BTCUSDT";

    @BeforeEach
    void setUp() {
        engine = new MatchEngine(TEST_SYMBOL, 100000);
    }

    // ====================== 基础功能测试 ======================

    @Test
    @Order(1)
    @DisplayName("基本订单操作测试")
    void testBasicOrderOperations() {
        addOrder(1001, 100, "buy", "50000", "1.0");
        assertEquals(1, engine.orderCount());
        assertNotNull(engine.getOrder(1001));

        CompactOrderBookEntry cancelled = engine.cancelOrder(1001);
        assertNotNull(cancelled);
        assertEquals(0, engine.orderCount());
        assertNull(engine.getOrder(1001));
    }

    @Test
    @Order(2)
    @DisplayName("买盘价格排序测试 - 降序")
    void testBuyBookDescending() {
        addOrder(1, 100, "buy", "50000", "1.0");
        addOrder(2, 100, "buy", "50200", "1.0");
        addOrder(3, 100, "buy", "49800", "1.0");

        assertEquals(toLong("50200"), engine.getBuyBook().bestPrice());
        assertEquals(toLong("50200"), engine.getBuyBook().priceAt(0));
        assertEquals(toLong("50000"), engine.getBuyBook().priceAt(1));
        assertEquals(toLong("49800"), engine.getBuyBook().priceAt(2));
    }

    @Test
    @Order(3)
    @DisplayName("卖盘价格排序测试 - 升序")
    void testSellBookAscending() {
        addOrder(1, 100, "sell", "50000", "1.0");
        addOrder(2, 100, "sell", "50200", "1.0");
        addOrder(3, 100, "sell", "49800", "1.0");

        assertEquals(toLong("49800"), engine.getSellBook().bestPrice());
        assertEquals(toLong("49800"), engine.getSellBook().priceAt(0));
        assertEquals(toLong("50000"), engine.getSellBook().priceAt(1));
        assertEquals(toLong("50200"), engine.getSellBook().priceAt(2));
    }

    @Test
    @Order(4)
    @DisplayName("同价位FIFO排序测试")
    void testSamePriceLevelFIFO() {
        addOrder(1, 100, "buy", "50000", "1.0");
        addOrder(2, 101, "buy", "50000", "2.0");
        addOrder(3, 102, "buy", "50000", "1.5");

        assertEquals(1, engine.getBuyBook().size());
        OrderList level = engine.getBuyBook().ordersAtPrice(toLong("50000"));
        assertNotNull(level);
        assertEquals(3, level.size());

        List<CompactOrderBookEntry> orders = level.toList();
        assertEquals(1, orders.get(0).orderId);
        assertEquals(2, orders.get(1).orderId);
        assertEquals(3, orders.get(2).orderId);
    }

    @Test
    @Order(5)
    @DisplayName("VIP优先级测试")
    void testVIPPriority() {
        // 普通订单
        addOrder(1, 100, "buy", "50000", "1.0", false);
        addOrder(2, 101, "buy", "50000", "1.0", false);
        // VIP订单
        addOrder(3, 102, "buy", "50000", "1.0", true);
        addOrder(4, 103, "buy", "50000", "1.0", true);

        OrderList level = engine.getBuyBook().ordersAtPrice(toLong("50000"));
        List<CompactOrderBookEntry> orders = level.toList();
        assertEquals(4, orders.size());

        // VIP订单应该在前面
        assertEquals(3, orders.get(0).orderId);
        assertEquals(4, orders.get(1).orderId);
        assertEquals(1, orders.get(2).orderId);
        assertEquals(2, orders.get(3).orderId);
    }

    @Test
    @Order(6)
    @DisplayName("账户订单数量跟踪测试")
    void testAccountOrderCount() {
        addOrder(1, 100, "buy", "50000", "1.0");
        addOrder(2, 100, "buy", "50100", "1.0");
        addOrder(3, 200, "sell", "51000", "1.0");

        assertEquals(2, engine.getAccountOrderCount(100));
        assertEquals(1, engine.getAccountOrderCount(200));
        assertEquals(0, engine.getAccountOrderCount(300));

        engine.cancelOrder(1);
        assertEquals(1, engine.getAccountOrderCount(100));
    }

    @Test
    @Order(7)
    @DisplayName("最大订单数限制测试")
    void testMaxOrdersLimit() {
        MatchEngine smallEngine = new MatchEngine(TEST_SYMBOL, 5);

        // 填满容量
        for (int i = 0; i < 5; i++) {
            addOrderToEngine(smallEngine, i, 100, "buy", String.valueOf(50000 + i), "1.0");
        }

        assertTrue(smallEngine.isFull());

        // 尝试添加更多订单
        CompactOrderBookEntry extra = smallEngine.acquireEntry();
        extra.orderId = 999;
        extra.accountId = 100;
        extra.price = toLong("60000");
        extra.quantity = toLong("1.0");
        extra.remainingQty = extra.quantity;
        extra.side = CompactOrderBookEntry.BUY;

        assertFalse(smallEngine.addOrder(extra));
    }

    @Test
    @Order(8)
    @DisplayName("从DTO加载订单测试")
    void testLoadOrdersFromDTO() {
        List<OrderBookEntry> orders = List.of(
                OrderBookEntry.builder()
                        .clientOrderId("1").accountId(100L).symbolId(TEST_SYMBOL)
                        .side("buy").price(new BigDecimal("50000")).quantity(new BigDecimal("1.0"))
                        .remainingQuantity(new BigDecimal("0.5")).requestTime(1000L).build(),
                OrderBookEntry.builder()
                        .clientOrderId("2").accountId(200L).symbolId(TEST_SYMBOL)
                        .side("sell").price(new BigDecimal("51000")).quantity(new BigDecimal("2.0"))
                        .remainingQuantity(new BigDecimal("2.0")).requestTime(2000L).build()
        );

        engine.loadOrders(orders);
        assertEquals(2, engine.orderCount());

        CompactOrderBookEntry order1 = engine.getOrder(1);
        assertNotNull(order1);
        assertEquals(toLong("0.5"), order1.remainingQty);
    }

    // ====================== 性能测试 ======================

    @ParameterizedTest
    @ValueSource(ints = {100, 500, 1000, 5000})
    @DisplayName("不同订单数量的性能测试")
    void testPerformanceWithVaryingCounts(int orderCount) {
        long startTime = System.nanoTime();

        for (int i = 0; i < orderCount; i++) {
            addOrder(i, 100 + (i % 100), i % 2 == 0 ? "buy" : "sell",
                    String.valueOf(50000 + (i % 1000)), "1.0");
        }

        long duration = System.nanoTime() - startTime;
        double avgLatency = (double) duration / orderCount / 1000; // microseconds

        System.out.printf("订单数: %d, 平均延迟: %.2f us%n", orderCount, avgLatency);
        assertTrue(avgLatency < 50, "平均延迟应小于50微秒");
    }

    @Test
    @Order(9)
    @DisplayName("单线程高吞吐量测试 - 模拟Disruptor消费模型")
    void testSingleThreadHighThroughput() {
        // 生产环境中 MatchEngine 由 Disruptor 单线程消费，不存在并发写入
        // 此测试验证单线程下大批量操作的正确性和吞吐量
        int totalOrders = 10000;

        long startNanos = System.nanoTime();
        for (int i = 0; i < totalOrders; i++) {
            addOrder(i, 100 + (i % 10),
                    i % 2 == 0 ? "buy" : "sell",
                    String.valueOf(50000 + (i % 100)), "1.0");
        }
        long durationNanos = System.nanoTime() - startNanos;

        assertEquals(totalOrders, engine.orderCount());

        // 验证买卖盘数据完整性
        int buyCount = engine.getBuyBook().allOrders().size();
        int sellCount = engine.getSellBook().allOrders().size();
        assertEquals(totalOrders, buyCount + sellCount);

        // 验证每个订单都可查
        for (int i = 0; i < totalOrders; i++) {
            assertNotNull(engine.getOrder(i), "Order " + i + " should exist");
        }

        double avgLatencyUs = (double) durationNanos / totalOrders / 1000;
        System.out.printf("单线程吞吐量测试: %d orders, avg %.2f us/order%n", totalOrders, avgLatencyUs);
    }

    @Test
    @Order(10)
    @DisplayName("内存效率测试")
    void testMemoryEfficiency() {
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        int orderCount = 5000;
        for (int i = 0; i < orderCount; i++) {
            addOrder(i, 100 + (i % 1000), i % 2 == 0 ? "buy" : "sell",
                    String.valueOf(50000 + (i % 100)), "1.0");
        }

        runtime.gc();
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryUsed = finalMemory - initialMemory;
        double memoryPerOrder = (double) memoryUsed / orderCount;

        System.out.printf("每订单内存: %.1f bytes%n", memoryPerOrder);
        assertTrue(memoryPerOrder < 1000, "每订单内存应该小于1KB");
    }

    // ====================== 边界情况测试 ======================

    @Test
    @Order(11)
    @DisplayName("价格转换一致性测试")
    void testPriceConversionConsistency() {
        BigDecimal price1 = new BigDecimal("50000");
        BigDecimal price2 = new BigDecimal("50000.00");

        long long1 = CompactOrderBookEntry.toLong(price1);
        long long2 = CompactOrderBookEntry.toLong(price2);
        assertEquals(long1, long2);

        BigDecimal back1 = CompactOrderBookEntry.toBigDecimal(long1);
        BigDecimal back2 = CompactOrderBookEntry.toBigDecimal(long2);

        assertEquals(0, price1.compareTo(back1));
        assertEquals(0, price2.compareTo(back2));
    }

    @Test
    @Order(12)
    @DisplayName("空值处理测试")
    void testNullHandling() {
        assertEquals(0, CompactOrderBookEntry.toLong((java.math.BigDecimal) null));

        CompactOrderBookEntry entry = new CompactOrderBookEntry();
        entry.remainingQty = 0;
        assertTrue(entry.isFilled());

        entry.remainingQty = 100;
        assertFalse(entry.isFilled());
    }

    @Test
    @Order(13)
    @DisplayName("订单池管理测试")
    void testEntryPoolManagement() {
        int initialPool = engine.poolAvailable();

        CompactOrderBookEntry entry1 = engine.acquireEntry();
        assertEquals(initialPool - 1, engine.poolAvailable());

        CompactOrderBookEntry entry2 = engine.acquireEntry();
        assertEquals(initialPool - 2, engine.poolAvailable());

        engine.releaseEntry(entry1);
        assertEquals(initialPool - 1, engine.poolAvailable());

        engine.releaseEntry(entry2);
        assertEquals(initialPool, engine.poolAvailable());
    }

    @Test
    @Order(14)
    @DisplayName("价格级别删除测试")
    void testPriceLevelRemoval() {
        addOrder(1, 100, "buy", "50000", "1.0");
        addOrder(2, 101, "buy", "50000", "2.0");
        assertEquals(1, engine.getBuyBook().size());

        // 删除第一个订单
        engine.cancelOrder(1);
        assertEquals(1, engine.getBuyBook().size()); // 价格级别仍存在

        // 删除最后一个订单
        engine.cancelOrder(2);
        assertEquals(0, engine.getBuyBook().size()); // 价格级别被删除
        assertTrue(engine.getBuyBook().isEmpty());
    }

    @Test
    @Order(15)
    @DisplayName("不存在订单的操作测试")
    void testNonExistentOrderOperations() {
        assertNull(engine.cancelOrder(99999));
        assertNull(engine.getOrder(99999));

        assertEquals(-1, engine.getBuyBook().indexOf(toLong("99999")));
        assertEquals(-1, engine.getSellBook().indexOf(toLong("99999")));
    }

    @Test
    @Order(16)
    @DisplayName("订单填充状态测试")
    void testOrderFilledStatus() {
        addOrder(1, 100, "buy", "50000", "1.0");
        CompactOrderBookEntry entry = engine.getOrder(1);

        assertFalse(entry.isFilled());

        // 模拟部分成交
        entry.remainingQty = toLong("0.5");
        assertFalse(entry.isFilled());

        // 模拟完全成交
        entry.remainingQty = 0;
        assertTrue(entry.isFilled());
    }

    @Test
    @Order(17)
    @DisplayName("订单移除测试")
    void testOrderRemoval() {
        addOrder(1, 100, "buy", "50000", "1.0");
        addOrder(2, 100, "buy", "50100", "1.0");
        assertEquals(2, engine.getAccountOrderCount(100));

        CompactOrderBookEntry entry = engine.getOrder(1);
        engine.removeFilledOrder(entry);
        assertEquals(1, engine.getAccountOrderCount(100));
        assertEquals(1, engine.orderCount());
    }

    @Test
    @Order(18)
    @DisplayName("订单池耗尽测试")
    void testEntryPoolExhaustion() {
        EntryPool pool = new EntryPool(2);

        // 获取所有可用条目
        CompactOrderBookEntry entry1 = pool.acquire();
        CompactOrderBookEntry entry2 = pool.acquire();
        assertNotNull(entry1);
        assertNotNull(entry2);
        assertEquals(0, pool.available());

        // 池耗尽时仍能创建新条目
        CompactOrderBookEntry entry3 = pool.acquire();
        assertNotNull(entry3);
        assertEquals(0, pool.available());
    }

    // ====================== 辅助方法 ======================

    private long toLong(String value) {
        return CompactOrderBookEntry.toLong(new BigDecimal(value));
    }

    private void addOrder(long orderId, long accountId, String side, String price, String qty) {
        addOrder(orderId, accountId, side, price, qty, false);
    }

    private void addOrder(long orderId, long accountId, String side, String price, String qty, boolean vip) {
        addOrderToEngine(engine, orderId, accountId, side, price, qty, vip);
    }

    private void addOrderToEngine(MatchEngine targetEngine, long orderId, long accountId, String side, String price, String qty) {
        addOrderToEngine(targetEngine, orderId, accountId, side, price, qty, false);
    }

    private void addOrderToEngine(MatchEngine targetEngine, long orderId, long accountId, String side, String price, String qty, boolean vip) {
        CompactOrderBookEntry entry = targetEngine.acquireEntry();
        entry.orderId = orderId;
        entry.accountId = accountId;
        entry.side = "buy".equals(side) ? CompactOrderBookEntry.BUY : CompactOrderBookEntry.SELL;
        entry.price = toLong(price);
        entry.quantity = toLong(qty);
        entry.remainingQty = entry.quantity;
        entry.requestTime = System.currentTimeMillis();
        entry.vip = vip;
        assertTrue(targetEngine.addOrder(entry));
    }
}