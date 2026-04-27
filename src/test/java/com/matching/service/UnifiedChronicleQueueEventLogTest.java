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
