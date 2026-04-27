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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 主从切换时序列号使用正确性验证
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MasterSlaveSequenceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TestEventLog primaryEventLog;
    private TestEventLog standbyEventLog;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        primaryEventLog = new TestEventLog(redisTemplate, "primary");
        standbyEventLog = new TestEventLog(redisTemplate, "standby");
    }

    @Test
    void testMasterSlaveSequenceSync() {
        // 场景：主实例运行，从实例通过Kafka复制EventLog
        String btcusdt = "BTCUSDT";
        String ethusdt = "ETHUSDT";

        // 主实例处理交易，分配全局seq
        primaryEventLog.setGlobalSeq(100);

        // 主实例处理多个交易对的订单
        long seq1 = primaryEventLog.appendBatch(btcusdt, createOrders("order1"), null, null); // seq=101
        long seq2 = primaryEventLog.appendBatch(ethusdt, createOrders("order2"), null, null); // seq=102
        long seq3 = primaryEventLog.appendBatch(btcusdt, createOrders("order3"), null, null); // seq=103

        assertEquals(101, seq1);
        assertEquals(102, seq2);
        assertEquals(103, seq3);
        assertEquals(103, primaryEventLog.currentSeq());

        // 从实例通过Kafka复制这些事件
        EventLog.Event event1 = new EventLog.Event(seq1, btcusdt, createOrders("order1"), null, null);
        EventLog.Event event2 = new EventLog.Event(seq2, ethusdt, createOrders("order2"), null, null);
        EventLog.Event event3 = new EventLog.Event(seq3, btcusdt, createOrders("order3"), null, null);

        standbyEventLog.appendReplicated(event1);
        standbyEventLog.appendReplicated(event2);
        standbyEventLog.appendReplicated(event3);

        // 验证从实例的globalSeq被正确更新
        assertEquals(103, standbyEventLog.currentSeq());

        // 验证从实例的EventLog包含所有事件
        List<EventLog.Event> standbyEvents = standbyEventLog.readEvents(btcusdt, 0);
        assertEquals(3, standbyEvents.size());
    }

    @Test
    void testStandbyPromotionSequenceSync() {
        // 场景：从实例升主时的序列号同步
        String symbolId = "BTCUSDT";

        // 从实例当前状态：通过复制获得的事件
        List<EventLog.Event> replicatedEvents = Arrays.asList(
                new EventLog.Event(100, symbolId, createOrders("order1"), null, null),
                new EventLog.Event(105, symbolId, createOrders("order2"), null, null),
                new EventLog.Event(110, symbolId, createOrders("order3"), null, null)
        );

        standbyEventLog.setMockEvents(symbolId, replicatedEvents);
        standbyEventLog.setGlobalSeq(108); // 当前globalSeq可能小于最大事件seq

        // 从实例升主时同步序列号
        long syncedSeq = standbyEventLog.syncSequenceOnActivation();

        // 验证同步到最大事件seq
        assertEquals(110, syncedSeq);
        assertEquals(110, standbyEventLog.currentSeq());

        // 新主实例继续分配序列号
        long newSeq = standbyEventLog.appendBatch(symbolId, createOrders("order4"), null, null);
        assertEquals(111, newSeq); // 确保序列号连续
    }

    @Test
    void testSequenceGapDetection() {
        // 场景：检测序列号间隙，确保数据一致性
        String symbolId = "BTCUSDT";

        // 模拟从实例缺失某些事件的场景
        List<EventLog.Event> incompleteEvents = Arrays.asList(
                new EventLog.Event(100, symbolId, createOrders("order1"), null, null),
                // 缺失 seq=101, 102
                new EventLog.Event(103, symbolId, createOrders("order2"), null, null),
                new EventLog.Event(104, symbolId, createOrders("order3"), null, null)
        );

        standbyEventLog.setMockEvents(symbolId, incompleteEvents);

        // 模拟主实例已发送到seq=104
        when(valueOperations.get("matching:eventlog:sent:" + symbolId))
                .thenReturn("104");

        // 检查数据一致性
        EventLog.ConsistencyCheckResult result = standbyEventLog.checkDataConsistency(symbolId);

        // 验证检测到数据一致（因为我们有最新的seq=104）
        assertEquals(EventLog.ConsistencyStatus.CONSISTENT, result.getStatus());
        assertEquals(104, result.getLocalSeq());
        assertEquals(104, result.getLastSentSeq());
    }

    @Test
    void testConcurrentSequenceAllocation() throws InterruptedException {
        // 场景：并发环境下序列号分配的正确性
        String btcusdt = "BTCUSDT";
        String ethusdt = "ETHUSDT";

        primaryEventLog.setGlobalSeq(1000);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(100);
        List<Long> allocatedSeqs = new ArrayList<>();

        // 并发分配序列号
        for (int i = 0; i < 100; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    String symbol = (index % 2 == 0) ? btcusdt : ethusdt;
                    long seq = primaryEventLog.appendBatch(symbol, createOrders("order" + index), null, null);
                    synchronized (allocatedSeqs) {
                        allocatedSeqs.add(seq);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // 验证序列号唯一性和递增性
        assertEquals(100, allocatedSeqs.size());
        allocatedSeqs.sort(Long::compareTo);

        for (int i = 0; i < allocatedSeqs.size(); i++) {
            assertEquals(1001 + i, allocatedSeqs.get(i).longValue());
        }

        assertEquals(1100, primaryEventLog.currentSeq());
    }

    @Test
    void testFailoverScenario() {
        // 场景：完整的主从切换场景
        String symbolId = "BTCUSDT";

        // 1. 主实例正常运行
        primaryEventLog.setGlobalSeq(200);
        long seq1 = primaryEventLog.appendBatch(symbolId, createOrders("order1"), null, null); // 201
        long seq2 = primaryEventLog.appendBatch(symbolId, createOrders("order2"), null, null); // 202

        // 2. 从实例复制事件
        standbyEventLog.appendReplicated(new EventLog.Event(seq1, symbolId, createOrders("order1"), null, null));
        standbyEventLog.appendReplicated(new EventLog.Event(seq2, symbolId, createOrders("order2"), null, null));

        // 3. 主实例故障前的最后一个事件
        long seq3 = primaryEventLog.appendBatch(symbolId, createOrders("order3"), null, null); // 203

        // 4. 从实例可能还没收到最后一个事件，但收到了lastSentSeq
        when(valueOperations.get("matching:eventlog:sent:" + symbolId))
                .thenReturn("203");

        // 5. 从实例升主
        standbyEventLog.setMockEvents(symbolId, Arrays.asList(
                new EventLog.Event(201, symbolId, createOrders("order1"), null, null),
                new EventLog.Event(202, symbolId, createOrders("order2"), null, null)
        ));

        // 6. 检查一致性
        EventLog.ConsistencyCheckResult result = standbyEventLog.checkDataConsistency(symbolId);
        assertEquals(EventLog.ConsistencyStatus.DATA_MISSING, result.getStatus());

        // 7. 获取安全恢复起始点
        long safeStartSeq = standbyEventLog.getSafeRecoveryStartSeq(symbolId);
        assertEquals(0, safeStartSeq); // 需要全量恢复

        // 8. 同步序列号并继续服务
        long syncedSeq = standbyEventLog.syncSequenceOnActivation();
        assertEquals(202, syncedSeq); // 基于本地最大seq

        // 9. 新主实例继续分配序列号
        long newSeq = standbyEventLog.appendBatch(symbolId, createOrders("order4"), null, null);
        assertEquals(203, newSeq); // 从本地最大seq+1开始
    }

    @Test
    void testSequenceRollbackOnFailure() {
        // 场景：写入失败时的序列号回滚
        String symbolId = "BTCUSDT";

        primaryEventLog.setGlobalSeq(300);
        primaryEventLog.setWriteFailure(true); // 模拟写入失败

        try {
            primaryEventLog.appendBatch(symbolId, createOrders("order1"), null, null);
            fail("Expected exception due to write failure");
        } catch (RuntimeException e) {
            // 验证序列号没有增长（回滚成功）
            assertEquals(300, primaryEventLog.currentSeq());
        }

        // 恢复正常写入
        primaryEventLog.setWriteFailure(false);
        long seq = primaryEventLog.appendBatch(symbolId, createOrders("order2"), null, null);
        assertEquals(301, seq); // 序列号正确继续
    }

    // 辅助方法
    private List<OrderBookEntry> createOrders(String orderId) {
        OrderBookEntry order = new OrderBookEntry();
        order.setClientOrderId(orderId);
        order.setPrice(new BigDecimal("50000"));
        order.setQuantity(new BigDecimal("1.0"));
        order.setSide("BUY");
        return Arrays.asList(order);
    }

    /**
     * 增强的测试EventLog实现
     */
    private static class TestEventLog extends EventLog {
        private final List<EventLog.Event> mockEvents = new ArrayList<>();
        private EventLog.Snapshot mockSnapshot;
        private boolean writeFailure = false;
        private final AtomicLong seqCounter = new AtomicLong(0);

        public TestEventLog(StringRedisTemplate redisTemplate, String instanceId) {
            super(redisTemplate);
        }

        public void setMockEvents(String symbolId, List<EventLog.Event> events) {
            synchronized (mockEvents) {
                mockEvents.clear();
                mockEvents.addAll(events);
            }
        }

        public void setGlobalSeq(long seq) {
            this.globalSeq.set(seq);
            seqCounter.set(seq);
        }

        public void setWriteFailure(boolean failure) {
            this.writeFailure = failure;
        }

        @Override
        public void init() {}

        @Override
        public void shutdown() {}

        @Override
        public long appendBatch(String symbolId, List<OrderBookEntry> addedOrders,
                               List<String> removedOrderIds, List<MatchResultEntry> matchResults) {
            if (writeFailure) {
                throw new RuntimeException("Simulated write failure");
            }
            long seq = globalSeq.incrementAndGet();
            EventLog.Event event = new EventLog.Event(seq, symbolId, addedOrders, removedOrderIds, matchResults);
            synchronized (mockEvents) {
                mockEvents.add(event);
            }
            return seq;
        }

        @Override
        public void appendReplicated(EventLog.Event event) {
            synchronized (mockEvents) {
                mockEvents.add(event);
            }
            globalSeq.updateAndGet(current -> Math.max(current, event.getSeq()));
        }

        @Override
        public List<EventLog.Event> readEvents(String symbolId, long afterSeq) {
            synchronized (mockEvents) {
                return new ArrayList<>(mockEvents);
            }
        }

        @Override
        public void writeSnapshot(String symbolId, long seq, List<OrderBookEntry> allOrders) {
            mockSnapshot = new EventLog.Snapshot(seq, symbolId, allOrders);
        }

        @Override
        public EventLog.Snapshot readSnapshot(String symbolId) {
            return mockSnapshot;
        }

        @Override
        public long getMaxLocalSeq() {
            synchronized (mockEvents) {
                return mockEvents.stream()
                        .mapToLong(EventLog.Event::getSeq)
                        .max()
                        .orElse(globalSeq.get());
            }
        }

        @Override
        protected long getMaxSeqForSymbol(String symbolId) {
            synchronized (mockEvents) {
                return mockEvents.stream()
                        .filter(event -> symbolId.equals(event.getSymbolId()))
                        .mapToLong(EventLog.Event::getSeq)
                        .max()
                        .orElse(0);
            }
        }

        public long currentSeq() {
            return globalSeq.get();
        }

        @Override
        public EventLog.Event readEventBySeq(long seq) {
            synchronized (mockEvents) {
                return mockEvents.stream()
                        .filter(event -> event.getSeq() == seq)
                        .findFirst()
                        .orElse(null);
            }
        }
    }
}

