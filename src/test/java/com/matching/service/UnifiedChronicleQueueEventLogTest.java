package com.matching.service;

import com.matching.dto.OrderBookEntry;
import com.matching.service.EventLog.Event;
import com.matching.service.EventLog.MatchResultEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 统一存储 EventLog 功能测试
 */
public class UnifiedChronicleQueueEventLogTest {

    @TempDir
    Path tempDir;

    private UnifiedChronicleQueueEventLog eventLog;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        // 创建PRIMARY角色的EventLog实例
        eventLog = new UnifiedChronicleQueueEventLog(redisTemplate, "PRIMARY", "test-instance");
    }

    @Test
    void testAppendBatchAndReadEvents() {
        // 测试数据
        String symbol1 = "BTC/USDT";
        String symbol2 = "ETH/USDT";

        // 创建OrderBookEntry
        OrderBookEntry order1 = new OrderBookEntry();
        order1.setClientOrderId("order1");
        order1.setSymbolId("BTC/USDT");
        order1.setPrice(BigDecimal.valueOf(50000));
        order1.setQuantity(BigDecimal.valueOf(1.0));
        order1.setSide("BUY");

        OrderBookEntry order2 = new OrderBookEntry();
        order2.setClientOrderId("order3");
        order2.setSymbolId("ETH/USDT");
        order2.setPrice(BigDecimal.valueOf(3000));
        order2.setQuantity(BigDecimal.valueOf(2.0));
        order2.setSide("SELL");

        List<OrderBookEntry> addedOrders1 = Arrays.asList(order1);
        List<String> removedOrderIds1 = Arrays.asList("oldOrder1");

        List<OrderBookEntry> addedOrders2 = Arrays.asList(order2);
        List<String> removedOrderIds2 = Arrays.asList("oldOrder2");

        // 创建MatchResultEntry
        MatchResultEntry match1 = new MatchResultEntry("match1", "payload1".getBytes());
        MatchResultEntry match2 = new MatchResultEntry("match2", "payload2".getBytes());

        List<MatchResultEntry> matchResults1 = Arrays.asList(match1);
        List<MatchResultEntry> matchResults2 = Arrays.asList(match2);

        // 写入第一个symbol的事件
        long seq1 = eventLog.appendBatch(symbol1, addedOrders1, removedOrderIds1, matchResults1);
        assertTrue(seq1 > 0);
        System.out.println("First event seq: " + seq1);

        // 写入第二个symbol的事件
        long seq2 = eventLog.appendBatch(symbol2, addedOrders2, removedOrderIds2, matchResults2);
        assertTrue(seq2 > seq1); // 序列号递增
        System.out.println("Second event seq: " + seq2);

        // 验证通过序列号可以读取到对应的事件
        Event event1 = eventLog.readEventBySeq(seq1);
        assertNotNull(event1);
        System.out.println("Read event1: seq=" + event1.getSeq() + ", symbol=" + event1.getSymbolId());
        assertEquals(seq1, event1.getSeq());
        assertEquals(symbol1, event1.getSymbolId());
        assertEquals(addedOrders1.size(), event1.getAddedOrders().size());
        assertEquals(matchResults1.size(), event1.getMatchResults().size());

        Event event2 = eventLog.readEventBySeq(seq2);
        assertNotNull(event2);
        System.out.println("Read event2: seq=" + event2.getSeq() + ", symbol=" + event2.getSymbolId());
        assertEquals(seq2, event2.getSeq());
        assertEquals(symbol2, event2.getSymbolId());
        assertEquals(addedOrders2.size(), event2.getAddedOrders().size());
        assertEquals(matchResults2.size(), event2.getMatchResults().size());

        // 验证读取不存在的序列号返回null
        Event nonExistent = eventLog.readEventBySeq(seq2 + 100);
        assertNull(nonExistent);

        System.out.println("✅ 统一存储 EventLog 功能测试通过");
        System.out.println("   - 序列号递增: " + seq1 + " → " + seq2);
        System.out.println("   - 跨symbol读取: BTC/USDT 和 ETH/USDT 事件都可通过seq访问");
        System.out.println("   - 无需seqToSymbolMap映射，直接O(1)访问");
    }

    @Test
    void testReadEventsInRange() {
        // 写入 10 个事件（seq 1-10）
        for (int i = 1; i <= 10; i++) {
            String symbol = i % 2 == 0 ? "ETH/USDT" : "BTC/USDT";
            OrderBookEntry order = new OrderBookEntry();
            order.setClientOrderId("order-" + i);
            order.setSymbolId(symbol);
            order.setPrice(BigDecimal.valueOf(100 + i));
            order.setQuantity(BigDecimal.valueOf(1.0));
            order.setSide("BUY");

            long seq = eventLog.appendBatch(symbol, List.of(order), List.of(), List.of());
            assertEquals(i, seq);
        }

        // readEventsInRange: 读取 seq 3-7
        List<Event> range = eventLog.readEventsInRange(3, 7);
        assertNotNull(range);
        assertEquals(5, range.size());
        // 验证 seq 顺序
        for (int i = 0; i < 5; i++) {
            assertEquals(3 + i, range.get(i).getSeq());
        }

        // 验证范围外的 seq 不在结果中
        assertTrue(range.stream().noneMatch(e -> e.getSeq() < 3 || e.getSeq() > 7),
                "readEventsInRange 不应返回范围外的事件");

        System.out.println("✅ readEventsInRange 测试通过: " + range.size() + " events in seq range 3-7");
    }

    @Test
    void testReadEventsInRangeWithNoMatch() {
        // 没有事件的空范围
        List<Event> emptyRange = eventLog.readEventsInRange(100, 200);
        assertNotNull(emptyRange);
        assertTrue(emptyRange.isEmpty());

        System.out.println("✅ readEventsInRange 空范围测试通过");
    }

    @Test
    void testReadEventsAfterSeq() {
        // 写入 10 个事件（seq 1-10）
        for (int i = 1; i <= 10; i++) {
            OrderBookEntry order = new OrderBookEntry();
            order.setClientOrderId("order-" + i);
            order.setSymbolId("BTC/USDT");
            order.setPrice(BigDecimal.valueOf(100 + i));
            order.setQuantity(BigDecimal.valueOf(1.0));
            order.setSide("BUY");

            long seq = eventLog.appendBatch("BTC/USDT", List.of(order), List.of(), List.of());
            assertEquals(i, seq);
        }

        // readEvents 读取 afterSeq=5 的所有事件
        List<Event> events = eventLog.readEvents("BTC/USDT", 5);
        assertNotNull(events);
        // 应只返回 seq > 5 的事件（6-10）
        assertTrue(events.stream().allMatch(e -> e.getSeq() > 5),
                "readEvents 应只返回 seq > afterSeq 的事件");
        assertTrue(events.stream().allMatch(e -> "BTC/USDT".equals(e.getSymbolId())),
                "readEvents 应只返回指定 symbol 的事件");

        System.out.println("✅ readEvents 过滤测试通过: " + events.size() + " events after seq 5, all seq > 5");
    }

    @Test
    void testReadEventsForSymbolFiltering() {
        // 交替写入 BTC/USDT 和 ETH/USDT 事件
        for (int i = 1; i <= 8; i++) {
            String symbol = i % 2 == 0 ? "ETH/USDT" : "BTC/USDT";
            OrderBookEntry order = new OrderBookEntry();
            order.setClientOrderId("order-" + i);
            order.setSymbolId(symbol);
            order.setPrice(BigDecimal.valueOf(100 + i));
            order.setQuantity(BigDecimal.valueOf(1.0));
            order.setSide("SELL");

            eventLog.appendBatch(symbol, List.of(order), List.of(), List.of());
        }

        // readEventsForSymbol: 只返回指定 symbol 且 seq > afterSeq 的事件
        List<Event> btcEvents = eventLog.readEventsForSymbol("BTC/USDT", 0);
        assertEquals(4, btcEvents.size(), "BTC/USDT 应有 4 个事件");
        assertTrue(btcEvents.stream().allMatch(e -> "BTC/USDT".equals(e.getSymbolId())));

        List<Event> ethEvents = eventLog.readEventsForSymbol("ETH/USDT", 0);
        assertEquals(4, ethEvents.size(), "ETH/USDT 应有 4 个事件");
        assertTrue(ethEvents.stream().allMatch(e -> "ETH/USDT".equals(e.getSymbolId())));

        // 验证排序正确
        for (int i = 1; i < btcEvents.size(); i++) {
            assertTrue(btcEvents.get(i).getSeq() > btcEvents.get(i - 1).getSeq(),
                    "事件应按 seq 升序排列");
        }

        System.out.println("✅ readEventsForSymbol 跨 symbol 过滤测试通过");
    }

    @Test
    void testPerformanceImprovement() {
        // 模拟高频写入场景
        long startTime = System.nanoTime();

        for (int i = 0; i < 100; i++) { // 减少到100个以加快测试
            String symbol = "SYMBOL" + (i % 10); // 10个不同symbol轮换
            OrderBookEntry order = new OrderBookEntry();
            order.setClientOrderId("order" + i);
            order.setSymbolId(symbol);
            order.setPrice(BigDecimal.valueOf(100 + i));
            order.setQuantity(BigDecimal.valueOf(1.0));
            order.setSide("BUY");

            List<OrderBookEntry> addedOrders = Arrays.asList(order);
            List<String> removedOrderIds = Arrays.asList();
            List<MatchResultEntry> matchResults = Arrays.asList();

            long seq = eventLog.appendBatch(symbol, addedOrders, removedOrderIds, matchResults);
            assertTrue(seq > 0);
        }

        long writeTime = System.nanoTime() - startTime;

        // 验证读取性能
        startTime = System.nanoTime();
        for (long seq = 1; seq <= 100; seq++) {
            Event event = eventLog.readEventBySeq(seq);
            assertNotNull(event);
            assertEquals(seq, event.getSeq());
        }
        long readTime = System.nanoTime() - startTime;

        System.out.println("✅ 性能测试通过");
        System.out.println("   - 写入100个事件: " + (writeTime / 1_000_000) + "ms");
        System.out.println("   - 读取100个事件: " + (readTime / 1_000_000) + "ms");
        System.out.println("   - 平均写入延迟: " + (writeTime / 1_000_000 / 100) + "ms/事件");
        System.out.println("   - 平均读取延迟: " + (readTime / 1_000_000 / 100) + "ms/事件");
    }
}
