package com.matching.service;

import com.matching.config.symbol.SymbolConfigService;
import com.matching.dto.OrderBookEntry;
import com.matching.engine.CompactOrderBookEntry;
import com.matching.engine.MatchEngine;
import com.matching.engine.PriceLevelBook;
import com.matching.ha.InstanceLeaderElection;
import com.matching.mq.EventLogReplicationConsumer;
import com.matching.service.orderbook.OrderBookManager;
import com.matching.util.ProtoConverter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HARollingUpgradeTest {

    private final Map<String, String> redisStore = new ConcurrentHashMap<>();
    private StringRedisTemplate sharedRedisTemplate;
    private ValueOperations<String, String> sharedValueOps;

    private HAService haServiceA;
    private EventLog eventLogA;
    private SnapshotService snapshotServiceA;
    private EventLogReplicationService replicationServiceA;
    private KafkaTemplate<String, byte[]> kafkaTemplateA;

    private HAService haServiceB;
    private EventLog eventLogB;
    private SnapshotService snapshotServiceB;
    private EventLogReplicationConsumer replicationConsumerB;

    private final List<ProducerRecord<String, byte[]>> kafkaChannel = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sharedRedisTemplate = mock(StringRedisTemplate.class);
        sharedValueOps = mock(ValueOperations.class);
        when(sharedRedisTemplate.opsForValue()).thenReturn(sharedValueOps);

        doAnswer(inv -> { redisStore.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(sharedValueOps).set(anyString(), anyString());
        when(sharedValueOps.get(anyString())).thenAnswer(inv -> redisStore.get(inv.getArgument(0)));

        eventLogA = mock(EventLog.class);
        when(eventLogA.currentSeq()).thenReturn(0L);
        snapshotServiceA = new SnapshotService(eventLogA, sharedRedisTemplate);
        kafkaTemplateA = mock(KafkaTemplate.class);
        replicationServiceA = new EventLogReplicationService(kafkaTemplateA, 100000);
        setField(replicationServiceA, "eventlogSyncTopic", "MATCHING_EVENTLOG_SYNC");
        setField(replicationServiceA, "instanceId", "node-A");
        setField(replicationServiceA, "sendTimeoutMs", 5000L);
        setField(replicationServiceA, "messageKey", "eventlog-sync");

        doAnswer(inv -> {
            kafkaChannel.add(inv.getArgument(0));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).when(kafkaTemplateA).send(any(ProducerRecord.class));

        haServiceA = createHAService("node-A");

        eventLogB = mock(EventLog.class);
        when(eventLogB.currentSeq()).thenReturn(0L);
        snapshotServiceB = new SnapshotService(eventLogB, sharedRedisTemplate);
        haServiceB = createHAService("node-B");
        replicationConsumerB = new EventLogReplicationConsumer(eventLogB, haServiceB);
        setField(replicationConsumerB, "instanceId", "node-B");
    }

    @Test
    void testFullRollingUpgradeFlow() throws Exception {
        assertEquals("STANDBY", haServiceA.getRole());
        assertEquals("STANDBY", haServiceB.getRole());

        haServiceA.forceSetStatus("PRIMARY", true, "ACTIVE");
        assertTrue(haServiceA.isPrimary());
        assertFalse(haServiceB.isPrimary());

        List<OrderBookEntry> ordersAfterMatching = List.of(
                buildOrder("order-1", 1001L, "BUY", "50000", "1.5", "1.0"),
                buildOrder("order-2", 1002L, "SELL", "51000", "2.0", "2.0"),
                buildOrder("order-3", 1003L, "BUY", "49500", "3.0", "3.0")
        );

        when(eventLogA.currentSeq()).thenReturn(100L);
        snapshotServiceA.snapshot("BTCUSDT", mockEngineWithOrders(ordersAfterMatching));

        EventLog.Event event1 = new EventLog.Event(98L, "BTCUSDT",
                List.of(buildOrder("order-1", 1001L, "BUY", "50000", "1.5", "1.0")), null, null);
        EventLog.Event event2 = new EventLog.Event(99L, "BTCUSDT",
                List.of(buildOrder("order-2", 1002L, "SELL", "51000", "2.0", "2.0")), null, null);
        EventLog.Event event3 = new EventLog.Event(100L, "BTCUSDT",
                List.of(buildOrder("order-3", 1003L, "BUY", "49500", "3.0", "3.0")), null, null);

        replicationServiceA.replicateEvent(event1);
        replicationServiceA.replicateEvent(event2);
        replicationServiceA.replicateEvent(event3);

        assertEquals(3, kafkaChannel.size());
        for (ProducerRecord<String, byte[]> sent : kafkaChannel) {
            assertEquals("MATCHING_EVENTLOG_SYNC", sent.topic());
            assertEquals("eventlog-sync", sent.key());
            var header = sent.headers().lastHeader(EventLogReplicationService.HEADER_SOURCE_INSTANCE);
            assertNotNull(header);
            assertEquals("node-A", new String(header.value(), StandardCharsets.UTF_8));
        }

        for (ProducerRecord<String, byte[]> sent : kafkaChannel) {
            ConsumerRecord<String, byte[]> consumerRecord = toConsumerRecord(sent);
            Acknowledgment ack = mock(Acknowledgment.class);
            replicationConsumerB.onEventLogSync(consumerRecord, ack);
            verify(ack).acknowledge();
        }

        verify(eventLogB, times(3)).appendReplicated(any(EventLog.Event.class));

        reset(eventLogB);
        when(eventLogB.appendBatch(any(), any(), any(), any())).thenReturn(1L);
        haServiceB = createHAService("node-B");

        snapshotServiceA.writeSnapshotToRedis("BTCUSDT", 100L, ordersAfterMatching);
        haServiceA.forceSetStatus("STANDBY", false, "INACTIVE");
        assertFalse(haServiceA.isPrimary());
        assertTrue(haServiceA.isStandby());

        EventLog.Snapshot snapshot = snapshotServiceB.readSnapshotFromRedis("BTCUSDT");
        assertNotNull(snapshot, "B 应该能从 Redis 读到 A 写的 Snapshot");
        assertEquals(100L, snapshot.getSeq());
        assertEquals("BTCUSDT", snapshot.getSymbolId());
        assertEquals(3, snapshot.getAllOrders().size());

        Map<String, OrderBookEntry> orderMap = new HashMap<>();
        for (OrderBookEntry order : snapshot.getAllOrders()) {
            orderMap.put(order.getClientOrderId(), order);
        }
        assertEquals(3, orderMap.size());
        assertTrue(orderMap.containsKey("order-1"));
        assertTrue(orderMap.containsKey("order-2"));
        assertTrue(orderMap.containsKey("order-3"));

        OrderBookEntry restoredOrder1 = orderMap.get("order-1");
        assertEquals(1001L, restoredOrder1.getAccountId());
        assertEquals("BUY", restoredOrder1.getSide());
        assertEquals(0, new BigDecimal("50000").compareTo(restoredOrder1.getPrice()));
        assertEquals(0, new BigDecimal("1.0").compareTo(restoredOrder1.getRemainingQuantity()));

        haServiceB.forceSetStatus("PRIMARY", true, "ACTIVE");
        assertTrue(haServiceB.isPrimary());
        assertFalse(haServiceA.isPrimary());
        assertEquals("node-B", haServiceB.getInstanceId());
    }

    @Test
    void testBothStandbyDuringSwitchWindow() {
        haServiceA.forceSetStatus("STANDBY", false, "INACTIVE");
        haServiceB.forceSetStatus("STANDBY", false, "INACTIVE");

        EventLog.Event event = new EventLog.Event(50L, "BTCUSDT", null, null, null);
        byte[] payload = ProtoConverter.serializeEvent(event);

        ConsumerRecord<String, byte[]> selfRecord = new ConsumerRecord<>("topic", 0, 0, "BTCUSDT", payload);
        selfRecord.headers().add(new RecordHeader(EventLogReplicationService.HEADER_SOURCE_INSTANCE,
                "node-B".getBytes(StandardCharsets.UTF_8)));
        replicationConsumerB.onEventLogSync(selfRecord, mock(Acknowledgment.class));
        verify(eventLogB, never()).appendReplicated(any());

        ConsumerRecord<String, byte[]> fromA = new ConsumerRecord<>("topic", 0, 1, "BTCUSDT", payload);
        fromA.headers().add(new RecordHeader(EventLogReplicationService.HEADER_SOURCE_INSTANCE,
                "node-A".getBytes(StandardCharsets.UTF_8)));
        replicationConsumerB.onEventLogSync(fromA, mock(Acknowledgment.class));
        verify(eventLogB).appendReplicated(any(EventLog.Event.class));
    }

    @Test
    void testSnapshotDataConsistencyAcrossInstances() {
        List<OrderBookEntry> orders = List.of(
                buildOrder("o1", 1L, "BUY", "50000.12345678", "1", "1"),
                buildOrder("o2", 2L, "SELL", "51000.99", "10", "0.5"),
                buildOrder("o3", 3L, "BUY", "49999.01", "0.001", "0.001")
        );

        when(eventLogA.currentSeq()).thenReturn(999L);
        snapshotServiceA.snapshot("ETHUSDT", mockEngineWithOrders(orders));

        EventLog.Snapshot snapshot = snapshotServiceB.readSnapshotFromRedis("ETHUSDT");
        assertNotNull(snapshot);
        assertEquals(999L, snapshot.getSeq());
        assertEquals("ETHUSDT", snapshot.getSymbolId());
        assertEquals(3, snapshot.getAllOrders().size());

        OrderBookEntry restored = snapshot.getAllOrders().get(0);
        assertEquals(0, new BigDecimal("50000.12345678").compareTo(restored.getPrice()));
        assertEquals(0, new BigDecimal("1").compareTo(restored.getQuantity()));
        assertEquals(0, new BigDecimal("1").compareTo(restored.getRemainingQuantity()));
    }

    @Test
    void testEventLogReplicationDataIntegrity() throws Exception {
        EventLog.Event original = new EventLog.Event(42L, "BTCUSDT",
                List.of(buildOrder("o1", 100L, "BUY", "50000", "1", "1")),
                List.of("removed-1", "removed-2"),
                List.of(new EventLog.MatchResultEntry("acc-100", new byte[]{1, 2, 3})));

        replicationServiceA.replicateEvent(original);
        assertEquals(1, kafkaChannel.size());

        byte[] payload = kafkaChannel.get(0).value();
        EventLog.Event deserialized = ProtoConverter.deserializeEvent(payload);
        assertEquals(42L, deserialized.getSeq());
        assertEquals("BTCUSDT", deserialized.getSymbolId());
        assertEquals(1, deserialized.getAddedOrders().size());
        assertEquals("o1", deserialized.getAddedOrders().get(0).getClientOrderId());
        assertEquals(2, deserialized.getRemovedOrderIds().size());
        assertEquals(1, deserialized.getMatchResults().size());
        assertArrayEquals(new byte[]{1, 2, 3}, deserialized.getMatchResults().get(0).getPayload());
    }

    @Test
    void testAfterActivateBSkipsReplication() {
        haServiceB.forceSetStatus("PRIMARY", true, "ACTIVE");

        EventLog.Event event = new EventLog.Event(1L, "BTCUSDT", null, null, null);
        byte[] payload = ProtoConverter.serializeEvent(event);
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>("topic", 0, 0, "BTCUSDT", payload);
        record.headers().add(new RecordHeader(EventLogReplicationService.HEADER_SOURCE_INSTANCE,
                "node-A".getBytes(StandardCharsets.UTF_8)));

        replicationConsumerB.onEventLogSync(record, mock(Acknowledgment.class));
        verify(eventLogB, never()).appendReplicated(any());
    }

    @Test
    void testMultiSymbolsSwitchover() {
        when(eventLogA.currentSeq()).thenReturn(200L);

        snapshotServiceA.writeSnapshotToRedis("BTCUSDT", 200L,
                List.of(buildOrder("btc-1", 1L, "BUY", "50000", "1", "1")));
        snapshotServiceA.writeSnapshotToRedis("ETHUSDT", 200L,
                List.of(buildOrder("eth-1", 2L, "SELL", "3000", "10", "10")));

        EventLog.Snapshot btcSnap = snapshotServiceB.readSnapshotFromRedis("BTCUSDT");
        EventLog.Snapshot ethSnap = snapshotServiceB.readSnapshotFromRedis("ETHUSDT");

        assertNotNull(btcSnap);
        assertNotNull(ethSnap);
        assertEquals("btc-1", btcSnap.getAllOrders().get(0).getClientOrderId());
        assertEquals("eth-1", ethSnap.getAllOrders().get(0).getClientOrderId());
        assertEquals(0, new BigDecimal("50000").compareTo(btcSnap.getAllOrders().get(0).getPrice()));
        assertEquals(0, new BigDecimal("3000").compareTo(ethSnap.getAllOrders().get(0).getPrice()));
    }

    @Test
    void testHAServiceStateTransitions() {
        HAService ha = createHAService("node-X");

        assertEquals("STANDBY", ha.getRole());
        assertFalse(ha.isPrimary());
        assertTrue(ha.isStandby());
        assertEquals("INACTIVE", ha.getStatus().getStatus());

        ha.forceSetStatus("PRIMARY", true, "ACTIVE");
        assertEquals("PRIMARY", ha.getRole());
        assertTrue(ha.isPrimary());
        assertFalse(ha.isStandby());
        assertEquals("ACTIVE", ha.getStatus().getStatus());
        assertEquals("node-X", ha.getStatus().getInstanceId());

        ha.forceSetStatus("STANDBY", false, "INACTIVE");
        assertEquals("STANDBY", ha.getRole());
        assertFalse(ha.isPrimary());
        assertTrue(ha.isStandby());

        ha.forceSetStatus("PRIMARY", true, "ACTIVE");
        assertTrue(ha.isPrimary());
    }

    // ========= Helpers =========

    private HAService createHAService(String instanceId) {
        StringRedisTemplate rt = mock(StringRedisTemplate.class);
        UnifiedChronicleQueueEventLog cqEvtLog = mock(UnifiedChronicleQueueEventLog.class);
        FastRecoveryService recoveryService = mock(FastRecoveryService.class);
        SymbolConfigService symbolConfigService = mock(SymbolConfigService.class);
        KafkaConsumerStartupService kafkaConsumerStartupService = mock(KafkaConsumerStartupService.class);
        SnapshotService snapshotService = mock(SnapshotService.class);
        OrderBookService orderBookService = mock(OrderBookService.class);
        InstanceLeaderElection leaderElection = mock(InstanceLeaderElection.class);
        EventLogReplicationSender eventLogReplicationSender = mock(EventLogReplicationSender.class);

        HAService ha = new HAService(rt, cqEvtLog, recoveryService, symbolConfigService,
                kafkaConsumerStartupService, snapshotService, orderBookService, leaderElection, eventLogReplicationSender);
        setField(ha, "instanceId", instanceId);
        setField(ha, "haEnabled", true);
        return ha;
    }

    private OrderBookEntry buildOrder(String clientOrderId, long accountId, String side,
                                      String price, String qty, String remainQty) {
        return OrderBookEntry.builder()
                .clientOrderId(clientOrderId)
                .accountId(accountId)
                .symbolId("BTCUSDT")
                .side(side)
                .price(new BigDecimal(price))
                .quantity(new BigDecimal(qty))
                .remainingQuantity(new BigDecimal(remainQty))
                .requestTime(System.currentTimeMillis())
                .vip(false)
                .build();
    }

    private MatchEngine mockEngineWithOrders(List<OrderBookEntry> orders) {
        MatchEngine engine = mock(MatchEngine.class);
        PriceLevelBook buyBook = mock(PriceLevelBook.class);
        PriceLevelBook sellBook = mock(PriceLevelBook.class);

        List<CompactOrderBookEntry> buyEntries = new ArrayList<>();
        List<CompactOrderBookEntry> sellEntries = new ArrayList<>();
        for (OrderBookEntry o : orders) {
            // 不调 fromEntry，直接用 mock 避免 parseLong 失败
            CompactOrderBookEntry compact = mock(CompactOrderBookEntry.class);
            when(compact.toEntry(anyString())).thenReturn(o);
            if ("BUY".equals(o.getSide())) buyEntries.add(compact);
            else sellEntries.add(compact);
        }
        when(buyBook.allOrders()).thenReturn(buyEntries);
        when(sellBook.allOrders()).thenReturn(sellEntries);
        when(engine.getBuyBook()).thenReturn(buyBook);
        when(engine.getSellBook()).thenReturn(sellBook);
        return engine;
    }

    private ConsumerRecord<String, byte[]> toConsumerRecord(ProducerRecord<String, byte[]> producer) {
        ConsumerRecord<String, byte[]> consumer = new ConsumerRecord<>(
                producer.topic(), 0, 0L, producer.key(), producer.value());
        producer.headers().forEach(h -> consumer.headers().add(h.key(), h.value()));
        return consumer;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            fail("Failed to set field " + fieldName + ": " + e.getMessage());
        }
    }
}
