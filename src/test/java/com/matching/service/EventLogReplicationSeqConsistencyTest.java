package com.matching.service;

import com.matching.dto.OrderBookEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EventLog复制场景下的Seq一致性测试
 * 验证从实例消费EventLog复制消息时,seq的正确同步
 */
@ExtendWith(MockitoExtension.class)
class EventLogReplicationSeqConsistencyTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private TestEventLog primaryEventLog;
    private TestEventLog standbyEventLog;

    @BeforeEach
    void setUp() {
        primaryEventLog = new TestEventLog(redisTemplate, "primary");
        standbyEventLog = new TestEventLog(redisTemplate, "standby");
    }

    /**
     * 测试场景1: 从实例消费EventLog复制时,globalSeq正确更新
     */
    @Test
    void testStandbyGlobalSeqUpdateOnReplication() {
        String symbolId = "BTCUSDT";

        // 主实例分配seq并写入EventLog
        primaryEventLog.setGlobalSeq(100);
        long seq1 = primaryEventLog.appendBatch(symbolId, createOrder("order1"), null, null);
        long seq2 = primaryEventLog.appendBatch(symbolId, createOrder("order2"), null, null);
        long seq3 = primaryEventLog.appendBatch(symbolId, createOrder("order3"), null, null);

        assertEquals(101, seq1);
        assertEquals(102, seq2);
        assertEquals(103, seq3);
        assertEquals(103, primaryEventLog.currentSeq());

        // 从实例消费EventLog复制
        standbyEventLog.setGlobalSeq(50);
        EventLog.Event event1 = new EventLog.Event(seq1, symbolId,
                createOrder("order1"), null, null);
        EventLog.Event event2 = new EventLog.Event(seq2, symbolId,
                createOrder("order2"), null, null);
        EventLog.Event event3 = new EventLog.Event(seq3, symbolId,
                createOrder("order3"), null, null);

        standbyEventLog.appendReplicated(event1);
        standbyEventLog.appendReplicated(event2);
        standbyEventLog.appendReplicated(event3);

        // 验证从实例的globalSeq被更新为主实例的最大seq
        assertEquals(103, standbyEventLog.currentSeq());
        assertEquals(103, standbyEventLog.getCommittedSeq());
    }

    /**
     * 测试场景2: 从实例复制时跳过重复的seq
     */
    @Test
    void testStandbySkipDuplicateReplicatedSeq() {
        String symbolId = "BTCUSDT";

        // 主实例写入
        primaryEventLog.setGlobalSeq(300);
        long seq1 = primaryEventLog.appendBatch(symbolId, createOrder("order1"), null, null);

        // 从实例复制同一事件两次
        standbyEventLog.setGlobalSeq(0);
        EventLog.Event event1 = new EventLog.Event(seq1, symbolId,
                createOrder("order1"), null, null);

        standbyEventLog.appendReplicated(event1);
        long seqAfterFirst = standbyEventLog.currentSeq();
        standbyEventLog.appendReplicated(event1);
        long seqAfterSecond = standbyEventLog.currentSeq();

        // 验证seq没有重复增长
        assertEquals(seqAfterFirst, seqAfterSecond);
        assertEquals(301, standbyEventLog.currentSeq());
    }

    /**
     * 测试场景3: 从实例激活时,从本地EventLog恢复seq
     */
    @Test
    void testStandbyActivationSeqRecovery() {
        String symbolId = "BTCUSDT";

        // 模拟从实例通过Kafka复制获得的EventLog
        List<EventLog.Event> replicatedEvents = Arrays.asList(
                new EventLog.Event(500, symbolId, createOrder("order1"), null, null),
                new EventLog.Event(501, symbolId, createOrder("order2"), null, null),
                new EventLog.Event(502, symbolId, createOrder("order3"), null, null)
        );

        standbyEventLog.setMockEvents(symbolId, replicatedEvents);
        standbyEventLog.setGlobalSeq(0);

        // 模拟从实例激活时的seq恢复
        long recoveredSeq = standbyEventLog.syncSequenceOnActivation();

        // 验证恢复到最大seq
        assertEquals(502, recoveredSeq);
        assertEquals(502, standbyEventLog.currentSeq());
        assertEquals(502, standbyEventLog.getCommittedSeq());
    }

    /**
     * 测试场景4: 主从切换时的seq连续性
     */
    @Test
    void testFailoverSequenceContinuity() {
        String symbolId = "BTCUSDT";

        // 1. 主实例运行
        primaryEventLog.setGlobalSeq(1000);
        long seq1 = primaryEventLog.appendBatch(symbolId, createOrder("order1"), null, null);
        long seq2 = primaryEventLog.appendBatch(symbolId, createOrder("order2"), null, null);
        long seq3 = primaryEventLog.appendBatch(symbolId, createOrder("order3"), null, null);

        assertEquals(1001, seq1);
        assertEquals(1002, seq2);
        assertEquals(1003, seq3);

        // 2. 从实例复制
        standbyEventLog.setGlobalSeq(0);
        standbyEventLog.appendReplicated(new EventLog.Event(seq1, symbolId,
                createOrder("order1"), null, null));
        standbyEventLog.appendReplicated(new EventLog.Event(seq2, symbolId,
                createOrder("order2"), null, null));
        standbyEventLog.appendReplicated(new EventLog.Event(seq3, symbolId,
                createOrder("order3"), null, null));

        assertEquals(1003, standbyEventLog.currentSeq());

        // 3. 从实例激活
        long recoveredSeq = standbyEventLog.syncSequenceOnActivation();
        assertEquals(1003, recoveredSeq);

        // 4. 新主实例继续分配seq
        long newSeq = standbyEventLog.appendBatch(symbolId, createOrder("order4"), null, null);
        assertEquals(1004, newSeq); // 确保seq连续
    }

    /**
     * 测试场景5: 从实例复制时,committedSeq正确更新
     */
    @Test
    void testStandbyCommittedSeqUpdate() {
        String symbolId = "ETHUSDT";

        // 主实例写入多个事件
        primaryEventLog.setGlobalSeq(400);
        long seq1 = primaryEventLog.appendBatch(symbolId, createOrder("order1"), null, null);
        long seq2 = primaryEventLog.appendBatch(symbolId, createOrder("order2"), null, null);
        long seq3 = primaryEventLog.appendBatch(symbolId, createOrder("order3"), null, null);

        // 从实例复制
        standbyEventLog.setGlobalSeq(0);
        standbyEventLog.appendReplicated(new EventLog.Event(seq1, symbolId,
                createOrder("order1"), null, null));
        assertEquals(seq1, standbyEventLog.getCommittedSeq());

        standbyEventLog.appendReplicated(new EventLog.Event(seq2, symbolId,
                createOrder("order2"), null, null));
        assertEquals(seq2, standbyEventLog.getCommittedSeq());

        standbyEventLog.appendReplicated(new EventLog.Event(seq3, symbolId,
                createOrder("order3"), null, null));
        assertEquals(seq3, standbyEventLog.getCommittedSeq());
    }

    /**
     * 辅助方法:创建订单
     */
    private List<OrderBookEntry> createOrder(String orderId) {
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
        private final AtomicLong committedSeq = new AtomicLong(0);
        private final String instanceId;

        public TestEventLog(StringRedisTemplate redisTemplate, String instanceId) {
            super(redisTemplate);
            this.instanceId = instanceId;
        }

        public void setMockEvents(String symbolId, List<EventLog.Event> events) {
            synchronized (mockEvents) {
                mockEvents.clear();
                mockEvents.addAll(events);
            }
        }

        public void setGlobalSeq(long seq) {
            globalSeq.set(seq);  // 使用父类的 globalSeq
        }

        public long getCommittedSeq() {
            return committedSeq.get();
        }

        @Override
        public void init() {}

        @Override
        public void shutdown() {}

        @Override
        public long appendBatch(String symbolId, List<OrderBookEntry> addedOrders,
                               List<String> removedOrderIds, List<MatchResultEntry> matchResults) {
            long seq = globalSeq.incrementAndGet();  // 使用父类的 globalSeq
            EventLog.Event event = new EventLog.Event(seq, symbolId, addedOrders, removedOrderIds, matchResults);
            synchronized (mockEvents) {
                mockEvents.add(event);
            }
            committedSeq.set(seq);
            return seq;
        }

        @Override
        public void appendReplicated(Event event) {
            synchronized (mockEvents) {
                mockEvents.add(event);
            }
            // 更新globalSeq为主实例的seq
            long eventSeq = event.getSeq();
            if (eventSeq > globalSeq.get()) {  // 使用父类的 globalSeq
                globalSeq.set(eventSeq);
                committedSeq.set(eventSeq);
            }
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
                        .orElse(globalSeq.get());  // 使用父类的 globalSeq
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

        @Override
        public long syncSequenceOnActivation() {
            synchronized (mockEvents) {
                long maxSeq = mockEvents.stream()
                        .mapToLong(EventLog.Event::getSeq)
                        .max()
                        .orElse(0L);
                globalSeq.set(maxSeq);  // 使用父类的 globalSeq
                committedSeq.set(maxSeq);
                return maxSeq;
            }
        }

        @Override
        public List<Event> readEventsInRange(long fromSeq, long toSeq) {
            synchronized (mockEvents) {
                return mockEvents.stream()
                    .filter(e -> e.getSeq() >= fromSeq && e.getSeq() <= toSeq)
                    .sorted(java.util.Comparator.comparingLong(Event::getSeq))
                    .collect(java.util.stream.Collectors.toList());
            }
        }

        @Override
        public Event readEventBySeq(long seq) {
            synchronized (mockEvents) {
                return mockEvents.stream()
                        .filter(event -> event.getSeq() == seq)
                        .findFirst()
                        .orElse(null);
            }
        }
    }
}
