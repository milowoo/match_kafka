package com.matching.service;

import com.matching.dto.OrderBookEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 性能测试和边界条件测试，验证大量数据和极端场景下的系统行为
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PerformanceAndBoundaryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TestEventLog eventLog;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        eventLog = new TestEventLog(redisTemplate);
    }

    @Test
    void testLargeScaleSequenceFiltering() {
        // 测试大规模数据下的序列号过滤性能
        String targetSymbol = "BTCUSDT";
        String[] otherSymbols = {"ETHUSDT", "ADAUSDT", "BNBUSDT", "XRPUSDT", "SOLUSDT"};

        List<EventLog.Event> largeEventSet = new ArrayList<>();
        Random random = new Random(12345); // 固定种子确保可重复

        // 生成10万个事件，目标交易对只占10%
        for (int i = 1; i <= 100000; i++) {
            String symbolId;
            if (i % 10 == 0) {
                symbolId = targetSymbol; // 10%是目标交易对
            } else {
                symbolId = otherSymbols[random.nextInt(otherSymbols.length)];
            }
            largeEventSet.add(createEvent(i, symbolId, "order" + i));
        }

        eventLog.setMockEvents(targetSymbol, largeEventSet);

        // 测试过滤性能
        long startTime = System.currentTimeMillis();
        List<EventLog.Event> filteredEvents = eventLog.readEventsForSymbol(targetSymbol, 50000);
        long endTime = System.currentTimeMillis();

        // 验证结果正确性：50000之后的100000个事件中，10%属于目标交易对
        assertEquals(5000, filteredEvents.size());

        // 验证所有事件都属于目标交易对
        for (EventLog.Event event : filteredEvents) {
            assertEquals(targetSymbol, event.getSymbolId());
            assertTrue(event.getSeq() > 50000);
        }

        // 验证事件按seq排序
        for (int i = 1; i < filteredEvents.size(); i++) {
            assertTrue(filteredEvents.get(i-1).getSeq() < filteredEvents.get(i).getSeq());
        }

        // 性能验证：10万事件过滤应该在合理时间内完成
        long processingTime = endTime - startTime;
        assertTrue(processingTime < 5000, "Processing time: " + processingTime + "ms should be < 5000ms");

        System.out.println("Filtered 100K events in " + processingTime + "ms, found " + filteredEvents.size() + " target events");
    }

    @Test
    void testConcurrentSequenceFiltering() throws InterruptedException {
        // 测试并发访问下的序列号过滤
        String symbolId = "BTCUSDT";

        // 准备大量测试数据
        List<EventLog.Event> concurrentTestEvents = new ArrayList<>();
        for (int i = 1; i <= 10000; i++) {
            String symbol = (i % 3 == 0) ? symbolId : "OTHER" + (i % 5);
            concurrentTestEvents.add(createEvent(i, symbol, "order" + i));
        }

        eventLog.setMockEvents(symbolId, concurrentTestEvents);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(50);
        List<List<EventLog.Event>> results = new ArrayList<>();

        // 并发执行过滤操作
        for (int i = 0; i < 50; i++) {
            final int startSeq = i * 100;
            executor.submit(() -> {
                try {
                    List<EventLog.Event> result = eventLog.readEventsForSymbol(symbolId, startSeq);
                    synchronized (results) {
                        results.add(result);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        // 验证所有结果都正确
        assertEquals(50, results.size());
        for (List<EventLog.Event> result : results) {
            // 验证所有事件都属于目标交易对
            for (EventLog.Event event : result) {
                assertEquals(symbolId, event.getSymbolId());
            }
            // 验证事件按seq排序
            for (int i = 1; i < result.size(); i++) {
                assertTrue(result.get(i-1).getSeq() < result.get(i).getSeq());
            }
        }
    }

    @Test
    void testExtremeSequenceGaps() {
        // 测试极端的序列号间隙场景
        String symbolId = "BTCUSDT";

        // 创建极端间隙的事件序列
        List<EventLog.Event> extremeGapEvents = Arrays.asList(
                createEvent(1, symbolId, "order1"),
                createEvent(1000000, symbolId, "order2"), // 巨大间隙
                createEvent(1000001, "ETHUSDT", "eth_order"), // 其他交易对
                createEvent(2000000, symbolId, "order3"), // 另一个巨大间隙
                createEvent(Long.MAX_VALUE - 1, symbolId, "order4") // 接近最大值
        );

        eventLog.setMockEvents(symbolId, extremeGapEvents);

        // 测试不同起始点的过滤
        List<EventLog.Event> fromZero = eventLog.readEventsForSymbol(symbolId, 0);
        assertEquals(4, fromZero.size());

        List<EventLog.Event> fromMillion = eventLog.readEventsForSymbol(symbolId, 1000000);
        assertEquals(2, fromMillion.size());
        assertEquals(2000000, fromMillion.get(0).getSeq());
        assertEquals(Long.MAX_VALUE - 1, fromMillion.get(1).getSeq());

        // 测试一致性检查
        when(valueOperations.get("matching:eventlog:sent:" + symbolId))
                .thenReturn(String.valueOf(Long.MAX_VALUE - 1));

        EventLog.ConsistencyCheckResult result = eventLog.checkDataConsistency(symbolId);
        assertEquals(EventLog.ConsistencyStatus.CONSISTENT, result.getStatus());
        assertEquals(Long.MAX_VALUE - 1, result.getLocalSeq());
    }

    @Test
    void testZeroAndNegativeSequences() {
        // 测试边界值：0和负数字列号
        String symbolId = "BTCUSDT";

        List<EventLog.Event> boundaryEvents = Arrays.asList(
                createEvent(0, symbolId, "order0"),   // seq=0
                createEvent(1, symbolId, "order1"),    // seq=1
                createEvent(2, "ETHUSDT", "eth_order") // 其他交易对
        );

        eventLog.setMockEvents(symbolId, boundaryEvents);

        // 测试从-1开始过滤（应该包含所有事件）
        List<EventLog.Event> fromNegative = eventLog.readEventsForSymbol(symbolId, -1);
        assertEquals(2, fromNegative.size());
        assertEquals(0, fromNegative.get(0).getSeq());
        assertEquals(1, fromNegative.get(1).getSeq());

        // 测试从0开始过滤
        List<EventLog.Event> fromZero = eventLog.readEventsForSymbol(symbolId, 0);
        assertEquals(1, fromZero.size());
        assertEquals(1, fromZero.get(0).getSeq());

        // 测试序列号同步
        eventLog.setGlobalSeq(0);
        long syncedSeq = eventLog.syncSequenceOnActivation();
        assertEquals(2, syncedSeq); // 同步到所有事件中的最大seq（含其他交易对）
    }

    @Test
    void testEmptyAndNullScenarios() {
        // 测试空数据和null场景
        String symbolId = "BTCUSDT";

        // 场景1：完全空的EventLog
        eventLog.setMockEvents(symbolId, new ArrayList<>());

        List<EventLog.Event> emptyResult = eventLog.readEventsForSymbol(symbolId, 0);
        assertEquals(0, emptyResult.size());

        long maxSeq = eventLog.getMaxSeqForSymbol(symbolId);
        assertEquals(0, maxSeq);

        // 场景2：只有其他交易对的事件
        List<EventLog.Event> otherSymbolEvents = Arrays.asList(
                createEvent(100, "ETHUSDT", "eth_order"),
                createEvent(101, "ADAUSDT", "ada_order")
        );

        eventLog.setMockEvents(symbolId, otherSymbolEvents);

        List<EventLog.Event> noTargetEvents = eventLog.readEventsForSymbol(symbolId, 0);
        assertEquals(0, noTargetEvents.size());

        long noTargetMaxSeq = eventLog.getMaxSeqForSymbol(symbolId);
        assertEquals(0, noTargetMaxSeq);

        // 场景3：一致性检查
        when(valueOperations.get("matching:eventlog:sent:" + symbolId))
                .thenReturn("0");

        EventLog.ConsistencyCheckResult result = eventLog.checkDataConsistency(symbolId);
        assertEquals(EventLog.ConsistencyStatus.CONSISTENT, result.getStatus());
    }

    @Test
    void testMemoryUsageWithLargeDataset() {
        // 测试大数据集下的内存使用
        String symbolId = "BTCUSDT";

        // 记录初始内存
        Runtime runtime = Runtime.getRuntime();
        runtime.gc(); // 强制垃圾回收
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        // 创建大量事件
        List<EventLog.Event> largeDataset = new ArrayList<>();
        for (int i = 1; i <= 50000; i++) {
            String symbol = (i % 2 == 0) ? symbolId : "OTHER";
            largeDataset.add(createEvent(i, symbol, "order" + i));
        }

        eventLog.setMockEvents(symbolId, largeDataset);

        // 执行多次过滤操作
        for (int i = 0; i < 10; i++) {
            List<EventLog.Event> filtered = eventLog.readEventsForSymbol(symbolId, i * 1000);
            assertNotNull(filtered);
        }

        // 检查内存使用
        runtime.gc();
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = finalMemory - initialMemory;

        // 内存增长应该在合理范围内（小于100MB）
        assertTrue(memoryIncrease < 100 * 1024 * 1024,
                "Memory increase: " + (memoryIncrease / 1024 / 1024) + "MB should be < 100MB");

        System.out.println("Memory increase: " + (memoryIncrease / 1024 / 1024) + "MB");
    }

    @Test
    void testSequenceOverflowScenario() {
        // 测试序列号溢出场景
        String symbolId = "BTCUSDT";

        // 模拟接近Long.MAX_VALUE的序列号
        long nearMaxValue = Long.MAX_VALUE - 10;

        List<EventLog.Event> overflowEvents = Arrays.asList(
                createEvent(nearMaxValue, symbolId, "order1"),
                createEvent(nearMaxValue + 1, symbolId, "order2"),
                createEvent(Long.MAX_VALUE, symbolId, "order3")
        );

        eventLog.setMockEvents(symbolId, overflowEvents);
        eventLog.setGlobalSeq(nearMaxValue - 5);

        // 测试序列号同步
        long syncedSeq = eventLog.syncSequenceOnActivation();
        assertEquals(Long.MAX_VALUE, syncedSeq);

        // 测试过滤功能
        List<EventLog.Event> filtered = eventLog.readEventsForSymbol(symbolId, nearMaxValue);
        assertEquals(2, filtered.size());
        assertEquals(nearMaxValue + 1, filtered.get(0).getSeq());
        assertEquals(Long.MAX_VALUE, filtered.get(1).getSeq());
    }

    // 辅助方法
    private EventLog.Event createEvent(long seq, String symbolId, String orderId) {
        List<OrderBookEntry> orders = Arrays.asList(createOrder(orderId));
        return new EventLog.Event(seq, symbolId, orders, null, null);
    }

    private OrderBookEntry createOrder(String orderId) {
        OrderBookEntry order = new OrderBookEntry();
        order.setClientOrderId(orderId);
        order.setPrice(new BigDecimal("50000"));
        order.setQuantity(new BigDecimal("1.0"));
        order.setSide("BUY");
        return order;
    }

    /**
     * 测试用的EventLog实现
     */
    private static class TestEventLog extends EventLog {
        private final List<EventLog.Event> mockEvents = new ArrayList<>();

        public TestEventLog(StringRedisTemplate redisTemplate) {
            super(redisTemplate);
        }

        public void setMockEvents(String symbolId, List<EventLog.Event> events) {
            synchronized (this) {
                this.mockEvents.clear();
                this.mockEvents.addAll(events);
            }
        }

        public void setGlobalSeq(long seq) {
            this.globalSeq.set(seq);
        }

        @Override
        public void init() {}

        @Override
        public void shutdown() {}

        @Override
        public long appendBatch(String symbolId, List<OrderBookEntry> addedOrders,
                               List<String> removedOrderIds, List<MatchResultEntry> matchResults) {
            return globalSeq.incrementAndGet();
        }

        @Override
        public void appendReplicated(EventLog.Event event) {
            synchronized (this) {
                mockEvents.add(event);
            }
            globalSeq.updateAndGet(current -> Math.max(current, event.getSeq()));
        }

        @Override
        public List<EventLog.Event> readEvents(String symbolId, long afterSeq) {
            synchronized (this) {
                return new ArrayList<>(mockEvents);
            }
        }

        @Override
        public void writeSnapshot(String symbolId, long seq, List<OrderBookEntry> allOrders) {}

        @Override
        public EventLog.Snapshot readSnapshot(String symbolId) {
            return null;
        }

        @Override
        public long getMaxLocalSeq() {
            synchronized (this) {
                return mockEvents.stream()
                        .mapToLong(EventLog.Event::getSeq)
                        .max()
                        .orElse(globalSeq.get());
            }
        }

        @Override
        protected long getMaxSeqForSymbol(String symbolId) {
            synchronized (this) {
                return mockEvents.stream()
                        .filter(event -> symbolId.equals(event.getSymbolId()))
                        .mapToLong(EventLog.Event::getSeq)
                        .max()
                        .orElse(0);
            }
        }

        @Override
        public Event readEventBySeq(long seq) {
            synchronized (this) {
                return mockEvents.stream()
                        .filter(event -> event.getSeq() == seq)
                        .findFirst()
                        .orElse(null);
            }
        }
    }
}

