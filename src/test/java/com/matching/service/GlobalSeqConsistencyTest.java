package com.matching.service;

import com.matching.dto.OrderBookEntry;
import com.matching.engine.MatchEngine;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 测试全局seq设计下的数据一致性和恢复逻辑
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GlobalSeqConsistencyTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private MatchEngine engine;

    private TestEventLog eventLog;
    private SnapshotService snapshotService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        eventLog = new TestEventLog(redisTemplate);
        snapshotService = new SnapshotService(eventLog, redisTemplate);
    }

    @Test
    void testGlobalSeqNonContinuousForSingleSymbol() {
        // 模拟全局seq分配场景：多个交易对并发获取seq
        String btcusdt = "BTCUSDT";
        String ethusdt = "ETHUSDT";

        // 模拟事件序列：全局seq连续，但单个交易对不连续
        List<EventLog.Event> allEvents = Arrays.asList(
                createEvent(100, btcusdt, "order1"),   // BTCUSDT
                createEvent(101, ethusdt, "order2"),   // ETHUSDT
                createEvent(102, btcusdt, "order3"),   // BTCUSDT
                createEvent(103, ethusdt, "order4"),   // ETHUSDT
                createEvent(104, btcusdt, "order5")    // BTCUSDT
        );

        eventLog.setMockEvents(btcusdt, allEvents);

        // 测试新的过滤方法
        List<EventLog.Event> btcEvents = eventLog.readEventsForSymbol(btcusdt, 99);

        // 验证只返回BTCUSDT的事件，且按seq排序
        assertEquals(3, btcEvents.size());
        assertEquals(100, btcEvents.get(0).getSeq());
        assertEquals(102, btcEvents.get(1).getSeq());
        assertEquals(104, btcEvents.get(2).getSeq());

        // 验证所有事件都属于BTCUSDT
        for (EventLog.Event event : btcEvents) {
            assertEquals(btcusdt, event.getSymbolId());
        }
    }

    @Test
    void testSnapshotRecoveryWithNonContinuousSeq() {
        String symbolId = "BTCUSDT";

        // 创建Snapshot（seq=100）
        List<OrderBookEntry> snapshotOrders = Arrays.asList(
                createOrder("order1", new BigDecimal("50000"), new BigDecimal("1.0"))
        );
        EventLog.Snapshot snapshot = new EventLog.Snapshot(100, symbolId, snapshotOrders);

        // 模拟后续事件：seq不连接（100之后是102, 105, 108）
        List<EventLog.Event> allEvents = Arrays.asList(
                createEvent(98, symbolId, "order0"),    // 在snapshot之前
                createEvent(100, symbolId, "order1"),    // snapshot时刻
                createEvent(102, symbolId, "order2"),    // 增量事件1
                createEvent(105, symbolId, "order3"),    // 增量事件2
                createEvent(108, symbolId, "order4")     // 增量事件3
        );

        eventLog.setMockEvents(symbolId, allEvents);
        eventLog.setMockSnapshot(symbolId, snapshot);

        // 模拟Redis返回真实编码的snapshot
        byte[] snapData = com.matching.util.ProtoConverter.serializeSnapshot(snapshot);
        String encoded = java.util.Base64.getEncoder().encodeToString(snapData);
        when(valueOperations.get("matching:snapshot:" + symbolId)).thenReturn(encoded);

        // 测试恢复
        boolean recovered = snapshotService.safeRecoverFromSnapshot(symbolId, engine);
        assertTrue(recovered);

        // 验证只加载了增量事件（seq > 100）
        List<EventLog.Event> incrementalEvents = eventLog.readEventsForSymbol(symbolId, 100);
        assertEquals(3, incrementalEvents.size());
        assertEquals(102, incrementalEvents.get(0).getSeq());
        assertEquals(105, incrementalEvents.get(1).getSeq());
        assertEquals(108, incrementalEvents.get(2).getSeq());
    }

    @Test
    void testDataConsistencyCheckWithSymbolSpecificSeq() {
        String symbolId = "BTCUSDT";

        // 模拟该交易对的事件：最大seq=105
        List<EventLog.Event> events = Arrays.asList(
                createEvent(100, symbolId, "order1"),
                createEvent(103, symbolId, "order2"),
                createEvent(105, symbolId, "order3")
        );

        eventLog.setMockEvents(symbolId, events);

        // 模拟lastSentSeq=103
        when(valueOperations.get("matching:eventlog:sent:" + symbolId))
                .thenReturn("103");

        // 测试一致性检查
        EventLog.ConsistencyCheckResult result = eventLog.checkDataConsistency(symbolId);
        assertEquals(EventLog.ConsistencyStatus.UNSYNCED_DATA, result.getStatus());
        assertEquals(105, result.getLocalSeq()); // 该交易对的最大seq
        assertEquals(103, result.getLastSentSeq());
        assertEquals(2, result.getSeqDiff());
    }

    @Test
    void testSequenceSyncOnActivation() {
        // 模拟主从切换场景
        String symbolId = "BTCUSDT";

        // 从实例本地EventLog最大seq=150
        List<EventLog.Event> localEvents = Arrays.asList(
                createEvent(100, symbolId, "order1"),
                createEvent(120, symbolId, "order2"),
               createEvent(150, symbolId, "order3")
        );

        eventLog.setMockEvents(symbolId, localEvents);
        eventLog.setGlobalSeq(140); // 当前globalSeq=140

        // 执行序列号同步
        long syncedSeq = eventLog.syncSequenceOnActivation();

        // 验证同步到最大值
        assertEquals(150, syncedSeq);
        assertEquals(150, eventLog.currentSeq());
    }

    @Test
    void testMasterSlaveSequenceConsistency() {
        // 测试主从切换时序列号的一致性
        String symbolId = "BTCUSDT";

        // 主实例场景：globalSeq=200
        eventLog.setGlobalSeq(200);

        // 模拟从实例接收到的复制事件
        EventLog.Event replicatedEvent = createEvent(205, symbolId, "order_from_master");

        // 从实例处理复制事件
        eventLog.appendReplicated(replicatedEvent);

        // 验证globalSeq被更新到更大值
        assertTrue(eventLog.currentSeq() >= 205);

        // 从实例升主时同步序列号
        List<EventLog.Event> allEvents = Arrays.asList(replicatedEvent);
        eventLog.setMockEvents(symbolId, allEvents);

        long syncedSeq = eventLog.syncSequenceOnActivation();

        // 验证序列号同步正确
        assertTrue(syncedSeq >= 205);
    }

    @Test
    void testEmptyEventLogBoundaryCondition() {
        String symbolId = "BTCUSDT";

        // 空EventLog场景
        eventLog.setMockEvents(symbolId, new ArrayList<>());
        eventLog.setGlobalSeq(0);

        // 测试序列号同步
        long syncedSeq = eventLog.syncSequenceOnActivation();

        // 验证边界条件处理
        assertEquals(0, syncedSeq);

        // 测试一致性检查
        when(valueOperations.get("matching:eventlog:sent:" + symbolId))
                .thenReturn("0");

        EventLog.ConsistencyCheckResult result = eventLog.checkDataConsistency(symbolId);
        assertEquals(EventLog.ConsistencyStatus.CONSISTENT, result.getStatus());
    }

    // 辅助方法
    private EventLog.Event createEvent(long seq, String symbolId, String orderId) {
        List<OrderBookEntry> orders = Arrays.asList(createOrder(orderId, new BigDecimal("50000"), new BigDecimal("1.0")));
        return new EventLog.Event(seq, symbolId, orders, null, null);
    }

    private OrderBookEntry createOrder(String orderId, BigDecimal price, BigDecimal quantity) {
        OrderBookEntry order = new OrderBookEntry();
        order.setClientOrderId(orderId);
        order.setSymbolId("BTCUSDT");
        order.setPrice(price);
        order.setQuantity(quantity);
        order.setRemainingQuantity(quantity);
        order.setSide("BUY");
        return order;
    }

    /**
     * 测试用的EventLog实现
     */
    private static class TestEventLog extends EventLog {
        private List<EventLog.Event> mockEvents = new ArrayList<>();
        private EventLog.Snapshot mockSnapshot;
        private long globalSeqValue = 0;

        public TestEventLog(StringRedisTemplate redisTemplate) {
            super(redisTemplate);
        }

        public void setMockEvents(String symbolId, List<EventLog.Event> events) {
            this.mockEvents = new ArrayList<>(events);
        }

        public void setMockSnapshot(String symbolId, EventLog.Snapshot snapshot) {
            this.mockSnapshot = snapshot;
        }

        public void setGlobalSeq(long seq) {
            this.globalSeqValue = seq;
            this.globalSeq.set(seq);
        }

        @Override
        public void init() {}

        @Override
        public void shutdown() {}

        @Override
        public long appendBatch(String symbolId, List<OrderBookEntry> addedOrders,
                               List<String> removedOrderIds, List<MatchResultEntry> matchResults) {
            long seq = globalSeq.incrementAndGet();
            EventLog.Event event = new EventLog.Event(seq, symbolId, addedOrders, removedOrderIds, matchResults);
            mockEvents.add(event);
            return seq;
        }

        @Override
        public void appendReplicated(EventLog.Event event) {
            mockEvents.add(event);
            globalSeq.updateAndGet(current -> Math.max(current, event.getSeq()));
        }

        @Override
        public List<EventLog.Event> readEvents(String symbolId, long afterSeq) {
            return new ArrayList<>(mockEvents);
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
            return mockEvents.stream()
                    .mapToLong(EventLog.Event::getSeq)
                    .max()
                    .orElse(globalSeqValue);
        }

        @Override
        public List<EventLog.Event> readEventsInRange(long fromSeq, long toSeq) {
            synchronized (mockEvents) {
                return mockEvents.stream()
                    .filter(e -> e.getSeq() >= fromSeq && e.getSeq() <= toSeq)
                    .sorted(java.util.Comparator.comparingLong(EventLog.Event::getSeq))
                    .collect(java.util.stream.Collectors.toList());
            }
        }

        @Override
        public EventLog.Event readEventBySeq(long seq) {
            return mockEvents.stream()
                    .filter(event -> event.getSeq() == seq)
                    .findFirst()
                    .orElse(null);
        }
    }
}

