package com.matching.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class PriceLevelBookTest {

    private PriceLevelBook buyBook;
    private PriceLevelBook sellBook;
    private CompactOrderBookEntry[] testEntries;

    @BeforeEach
    void setUp() {
        buyBook = new PriceLevelBook(true);
        sellBook = new PriceLevelBook(false);

        testEntries = new CompactOrderBookEntry[10];
        for (int i = 0; i < 10; i++) {
            CompactOrderBookEntry entry = new CompactOrderBookEntry();
            entry.orderId = i + 1;
            entry.accountId = 1000 + i;
            entry.price = CompactOrderBookEntry.toLong(java.math.BigDecimal.valueOf(100 + i));
            entry.quantity = CompactOrderBookEntry.toLong(java.math.BigDecimal.valueOf(10 * (i + 1)));
            entry.remainingQty = entry.quantity;
            entry.side = CompactOrderBookEntry.BUY;
            entry.requestTime = System.currentTimeMillis();
            testEntries[i] = entry;
        }
    }

    @Test
    @DisplayName("基本操作功能测试")
    void testBasicOperations() {
        assertTrue(buyBook.isEmpty());
        assertEquals(0, buyBook.size());
        assertEquals(0, buyBook.bestPrice());

        for (CompactOrderBookEntry entry : testEntries) {
            buyBook.addOrder(entry);
        }

        assertFalse(buyBook.isEmpty());
        assertEquals(testEntries.length, buyBook.size());
        assertTrue(buyBook.bestPrice() > 0);
    }

    @Test
    @DisplayName("价格查找功能测试")
    void testPriceLookup() {
        for (CompactOrderBookEntry entry : testEntries) {
            buyBook.addOrder(entry);
        }

        for (CompactOrderBookEntry entry : testEntries) {
            OrderList orders = buyBook.ordersAtPrice(entry.price);
            assertNotNull(orders);
            assertFalse(orders.isEmpty());
        }

        long nonExistentPrice = CompactOrderBookEntry.toLong(java.math.BigDecimal.valueOf(999));
        assertNull(buyBook.ordersAtPrice(nonExistentPrice));
    }

    @Test
    @DisplayName("索引访问功能测试")
    void testIndexAccess() {
        for (CompactOrderBookEntry entry : testEntries) {
            buyBook.addOrder(entry);
        }

        int size = buyBook.size();
        for (int i = 0; i < size; i++) {
            assertTrue(buyBook.priceAt(i) > 0);
            assertNotNull(buyBook.ordersAt(i));
        }

        assertEquals(0, buyBook.priceAt(-1));
        assertEquals(0, buyBook.priceAt(size));
        assertNull(buyBook.ordersAt(-1));
        assertNull(buyBook.ordersAt(size));
    }

    @Test
    @DisplayName("删除操作功能测试")
    void testRemovalOperations() {
        for (CompactOrderBookEntry entry : testEntries) {
            buyBook.addOrder(entry);
        }

        int initialSize = buyBook.size();

        CompactOrderBookEntry toRemove = testEntries[5];
        boolean removed = buyBook.removeOrder(toRemove.price, toRemove.orderId);
        assertTrue(removed);
        assertEquals(initialSize - 1, buyBook.size());

        CompactOrderBookEntry toRemove2 = testEntries[3];
        buyBook.removeEntry(toRemove2);
        assertEquals(initialSize - 2, buyBook.size());
    }

    @Test
    @DisplayName("最佳价格级别删除测试")
    void testBestLevelRemoval() {
        for (int i = 0; i < 5; i++) {
            buyBook.addOrder(testEntries[i]);
        }

        long bestPriceBefore = buyBook.bestPrice();
        assertTrue(bestPriceBefore > 0);

        buyBook.removeBestLevel();
        assertEquals(4, buyBook.size());

        long bestPriceAfter = buyBook.bestPrice();
        assertNotEquals(bestPriceBefore, bestPriceAfter);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("买卖盘排序测试")
    void testBuySellOrdering(boolean descending) {
        PriceLevelBook book = new PriceLevelBook(descending);

        long[] prices = {105, 101, 103, 107, 102, 106, 104};
        for (long price : prices) {
            CompactOrderBookEntry entry = new CompactOrderBookEntry();
            entry.orderId = price;
            entry.price = price;
            entry.quantity = 100;
            entry.remainingQty = 100;
            book.addOrder(entry);
        }

        assertEquals(prices.length, book.size());

        for (int i = 1; i < book.size(); i++) {
            long prevPrice = book.priceAt(i - 1);
            long currPrice = book.priceAt(i);
            if (descending) {
                assertTrue(prevPrice >= currPrice, "买盘应该降序排列");
            } else {
                assertTrue(prevPrice <= currPrice, "卖盘应该升序排列");
            }
        }
    }

    @Test
    @DisplayName("大量数据功能测试")
    void testLargeDataHandling() {
        final int LARGE_SIZE = 1000;

        for (int i = 0; i < LARGE_SIZE; i++) {
            CompactOrderBookEntry entry = new CompactOrderBookEntry();
            entry.orderId = i;
            entry.price = 1000 + (i % 100);
            entry.quantity = 10;
            entry.remainingQty = 10;
            buyBook.addOrder(entry);
        }

        assertEquals(100, buyBook.size());
        assertTrue(buyBook.bestPrice() > 0);

        Random random = new Random(42);
        for (int i = 0; i < 100; i++) {
            int indexToRemove = random.nextInt(LARGE_SIZE);
            long price = 1000 + (indexToRemove % 100);
            buyBook.removeOrder(price, indexToRemove);
        }

        assertTrue(buyBook.size() <= 100);
    }

    @Test
    @DisplayName("遍历功能测试")
    void testIteration() {
        for (CompactOrderBookEntry entry : testEntries) {
            buyBook.addOrder(entry);
        }

        Set<Map.Entry<Long, OrderList>> levels = buyBook.levels();
        assertEquals(buyBook.size(), levels.size());

        List<Long> prices = new ArrayList<>();
        for (Map.Entry<Long, OrderList> entry : levels) {
            prices.add(entry.getKey());
        }

        for (int i = 1; i < prices.size(); i++) {
            assertTrue(prices.get(i - 1) >= prices.get(i), "买盘价格应该降序排列");
        }
    }

    @Test
    @DisplayName("边界条件测试")
    void testEdgeCases() {
        assertEquals(0, buyBook.bestPrice());
        assertNull(buyBook.firstEntry());
        assertTrue(buyBook.levels().isEmpty());
        assertTrue(buyBook.allOrders().isEmpty());

        CompactOrderBookEntry singleEntry = testEntries[0];
        buyBook.addOrder(singleEntry);

        assertEquals(singleEntry.price, buyBook.bestPrice());
        assertEquals(1, buyBook.size());
        assertNotNull(buyBook.firstEntry());

        buyBook.removeEntry(singleEntry);
        assertTrue(buyBook.isEmpty());
        assertEquals(0, buyBook.size());
    }

    @Test
    @DisplayName("性能基准测试")
    void testPerformance() {
        final int OPERATIONS = 10000;

        long startTime = System.nanoTime();
        for (int i = 0; i < OPERATIONS; i++) {
            CompactOrderBookEntry entry = new CompactOrderBookEntry();
            entry.orderId = i;
            entry.price = 1000 + (i % 100);
            entry.quantity = 10;
            entry.remainingQty = 10;
            buyBook.addOrder(entry);
        }
        long insertTime = System.nanoTime() - startTime;

        startTime = System.nanoTime();
        for (int i = 0; i < OPERATIONS; i++) {
            long price = 1000 + (i % 100);
            buyBook.ordersAtPrice(price);
        }
        long lookupTime = System.nanoTime() - startTime;

        System.out.printf("插入%d个订单耗时: %.2f ms%n", OPERATIONS, insertTime / 1_000_000.0);
        System.out.printf("查找%d次耗时: %.2f ms%n", OPERATIONS, lookupTime / 1_000_000.0);

        assertTrue(insertTime / OPERATIONS < 50000, "平均插入时间应小于50微秒");
        assertTrue(lookupTime / OPERATIONS < 5000, "平均查找时间应小于5微秒");
    }
}
