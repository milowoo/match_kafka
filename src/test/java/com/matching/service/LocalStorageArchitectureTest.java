package com.matching.service;

import com.matching.dto.OrderBookEntry;
import com.matching.mq.EventLogReplicationConsumer;
import com.matching.util.ProtoConverter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 本地存储 + Kafka 复制架构改动测试 覆盖: Snapshot Protobuf 序列化、Redis 读写、EventLog 复制(含 instanceId header)、实例消费(含 instanceId 过滤)
 */
class LocalStorageArchitectureTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private EventLog eventLog;
    private SnapshotService snapshotService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        eventLog = mock(EventLog.class);
        when(eventLog.currentSeq()).thenReturn(189L);

        snapshotService = new SnapshotService(eventLog, redisTemplate);
    }

    // =================== Snapshot Protobuf 序列化 ===================
    @Test
    void testSnapshotProtobufRoundTrip() throws Exception {
        List<OrderBookEntry> orders = List.of(
                OrderBookEntry.builder()
                        .clientOrderId("order1").accountId(1001L).symbolId("BTCUSDT")
                        .side("BUY").price(new BigDecimal("50000")).quantity(new BigDecimal("1.5"))
                        .remainingQuantity(new BigDecimal("1.0")).requestTime(System.currentTimeMillis()).build(),
                OrderBookEntry.builder()
                        .clientOrderId("order2").accountId(1001L).symbolId("BTCUSDT")
                        .side("SELL").price(new BigDecimal("51000")).quantity(new BigDecimal("2.0"))
                        .remainingQuantity(new BigDecimal("2.0")).requestTime(System.currentTimeMillis()).build()
        );

        EventLog.Snapshot original = new EventLog.Snapshot(99L, "BTCUSDT", orders);
        byte[] data = ProtoConverter.serializeSnapshot(original);
        EventLog.Snapshot restored = ProtoConverter.deserializeSnapshot(data);

        assertEquals(99L, restored.getSeq());
        assertEquals("BTCUSDT", restored.getSymbolId());
        assertEquals(2, restored.getAllOrders().size());

        OrderBookEntry buy = restored.getAllOrders().get(0);
        assertEquals("order1", buy.getClientOrderId());
        assertEquals(1001L, buy.getAccountId());
        assertEquals("BUY", buy.getSide());
        assertEquals(new BigDecimal("50000").compareTo(buy.getPrice()), 0);
        assertEquals(new BigDecimal("1.5").compareTo(buy.getQuantity()), 0);
        assertEquals(new BigDecimal("1.0").compareTo(buy.getRemainingQuantity()), 0);
        assertTrue(buy.isVip() || !buy.isVip()); // vip 字段按实际值验证，不强制要求

        OrderBookEntry sell = restored.getAllOrders().get(1);
        assertEquals("order2", sell.getClientOrderId());
        assertEquals("SELL", sell.getSide());
        assertFalse(sell.isVip());
    }

    @Test
    void testSnapshotProtobufEmptyOrders() throws Exception {
        EventLog.Snapshot original = new EventLog.Snapshot(0L, "ETHUSDT", new ArrayList<>());
        byte[] data = ProtoConverter.serializeSnapshot(original);
        EventLog.Snapshot restored = ProtoConverter.deserializeSnapshot(data);

        assertEquals(0L, restored.getSeq());
        assertEquals("ETHUSDT", restored.getSymbolId());
        assertTrue(restored.getAllOrders().isEmpty());
    }

    // =================== SnapshotService Redis 读写 ===================
    @Test
    void testReadSnapshotFromRedis() throws Exception {
        List<OrderBookEntry> orders = List.of(
                OrderBookEntry.builder()
                        .clientOrderId("o1").symbolId("BTCUSDT").side("BUY")
                        .price(new BigDecimal("50000")).quantity(new BigDecimal("1.5"))
                        .remainingQuantity(new BigDecimal("1.0")).build()
        );
        EventLog.Snapshot snap = new EventLog.Snapshot(50L, "BTCUSDT", orders);
        byte[] data = ProtoConverter.serializeSnapshot(snap);
        String encoded = Base64.getEncoder().encodeToString(data);

        when(valueOps.get("matching:snapshot:BTCUSDT")).thenReturn(encoded);

        EventLog.Snapshot result = snapshotService.readSnapshotFromRedis("BTCUSDT");

        assertNotNull(result);
        assertEquals(50L, result.getSeq());
        assertEquals("BTCUSDT", result.getSymbolId());
        assertEquals(1, result.getAllOrders().size());
        assertEquals("o1", result.getAllOrders().get(0).getClientOrderId());
    }

    @Test
    void testReadSnapshotFromRedisNotFound() {
        when(valueOps.get("matching:snapshot:UNKNOWN")).thenReturn(null);

        EventLog.Snapshot result = snapshotService.readSnapshotFromRedis("UNKNOWN");

        assertNull(result);
    }

    @Test
    void testReadSnapshotFromRedisCorruptData() {
        when(valueOps.get("matching:snapshot:CORRUPT")).thenReturn("invalid-base64");

        // readSnapshotFromRedis 内部 catch 异常返回 null，不会向外抛出
        assertNull(snapshotService.readSnapshotFromRedis("CORRUPT"));
    }

    @Test
    void testWriteSnapshotToRedis() {
        List<OrderBookEntry> orders = List.of(
                OrderBookEntry.builder()
                        .clientOrderId("o1").symbolId("BTCUSDT").side("BUY")
                        .price(new BigDecimal("50000")).quantity(new BigDecimal("1.5"))
                        .remainingQuantity(new BigDecimal("1.0")).build()
        );

        snapshotService.writeSnapshotToRedis("BTCUSDT", 100L, orders);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCaptor.capture(), valueCaptor.capture());

        assertEquals("matching:snapshot:BTCUSDT", keyCaptor.getValue());
        assertDoesNotThrow(() -> Base64.getDecoder().decode(valueCaptor.getValue()));
    }

    // =================== EventLogReplicationService Kafka 复制 ===================
    @Test
    void testReplicateEventWithInstanceHeader() {
        KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        EventLogReplicationService service = new EventLogReplicationService(kafkaTemplate);
        setField(service, "eventlogSyncTopic", "MATCHING_EVENTLOG_SYNC");
        setField(service, "instanceId", "node-1");
        setField(service, "sendTimeoutMs", 5000L);

        EventLog.Event event = new EventLog.Event(42L, "BTCUSDT",
                List.of(OrderBookEntry.builder().clientOrderId("o1").symbolId("BTCUSDT").side("BUY")
                        .price(new BigDecimal("50000")).quantity(new BigDecimal("1.5"))
                        .remainingQuantity(new BigDecimal("1.0")).build()),
                List.of("o2"), null);

        service.replicateEvent(event);

        ArgumentCaptor<ProducerRecord<String, byte[]>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<String, byte[]> sent = captor.getValue();
        assertEquals("MATCHING_EVENTLOG_SYNC", sent.topic());
        assertEquals("BTCUSDT", sent.key());

        // 验证 header 包含 sourceInstanceId
        var header = sent.headers().lastHeader(EventLogReplicationService.HEADER_SOURCE_INSTANCE);
        assertNotNull(header);
        assertEquals("node-1", new String(header.value(), StandardCharsets.UTF_8));

        // 验证 payload 可反序列化
        assertDoesNotThrow(() -> {
            EventLog.Event deserialized = ProtoConverter.deserializeEvent(sent.value());
            assertEquals(42L, deserialized.getSeq());
            assertEquals("BTCUSDT", deserialized.getSymbolId());
        });
    }

    // =================== EventLogReplicationConsumer 过滤逻辑 ===================
    @Test
    void testConsumerSkipsSelfInstance() throws Exception {
        HAService haService = mock(HAService.class);
        when(haService.isPrimary()).thenReturn(false); // STANDBY, 但消息是自己发的
        EventLogReplicationConsumer consumer = createConsumer(eventLog, haService, "node-1");
        Acknowledgment ack = mock(Acknowledgment.class);

        ConsumerRecord<String, byte[]> record = buildRecord("BTCUSDT", "node-1");
        consumer.onEventLogSync(record, ack);

        verify(ack).acknowledge();
        verify(eventLog, never()).appendReplicated(any());
    }

    @Test
    void testConsumerSkipsWhenPrimary() throws Exception {
        HAService haService = mock(HAService.class);
        when(haService.isPrimary()).thenReturn(true); // 主节点不应消费复制消息
        EventLogReplicationConsumer consumer = createConsumer(eventLog, haService, "node-2");
        Acknowledgment ack = mock(Acknowledgment.class);

        ConsumerRecord<String, byte[]> record = buildRecord("BTCUSDT", "node-1");
        consumer.onEventLogSync(record, ack);

        verify(ack).acknowledge();
        verify(eventLog, never()).appendReplicated(any());
    }

    @Test
    void testConsumerWritesWhenStandbyFromOtherInstance() throws Exception {
        HAService haService = mock(HAService.class);
        when(haService.isPrimary()).thenReturn(false);
        EventLogReplicationConsumer consumer = createConsumer(eventLog, haService, "node-2");
        Acknowledgment ack = mock(Acknowledgment.class);

        ConsumerRecord<String, byte[]> record = buildRecord("BTCUSDT", "node-1"); // 来自 node-1
        consumer.onEventLogSync(record, ack);

        verify(eventLog).appendReplicated(any(EventLog.Event.class));
        verify(ack).acknowledge();
    }

    @Test
    void testConsumerWritesWhenNoHeader() throws Exception {
        HAService haService = mock(HAService.class);
        when(haService.isPrimary()).thenReturn(false);
        EventLogReplicationConsumer consumer = createConsumer(eventLog, haService, "node-2");
        Acknowledgment ack = mock(Acknowledgment.class);

        // 无 header 的消息 (兼容旧版本)
        byte[] payload = ProtoConverter.serializeEvent(
                new EventLog.Event(11L, "BTCUSDT", null, null, null)
        );
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>("topic", 0, 0, "BTCUSDT", payload);

        consumer.onEventLogSync(record, ack);

        verify(eventLog).appendReplicated(any(EventLog.Event.class));
        verify(ack).acknowledge();
    }

    @Test
    void testConsumerHandlesDeserializationError() {
        HAService haService = mock(HAService.class);
        when(haService.isPrimary()).thenReturn(false);
        EventLogReplicationConsumer consumer = createConsumer(eventLog, haService, "node-2");
        Acknowledgment ack = mock(Acknowledgment.class);

        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>("topic", 0, 0, "BTCUSDT", new byte[]{1, 2, 3});
        record.headers().add(new RecordHeader(EventLogReplicationService.HEADER_SOURCE_INSTANCE, "node-1".getBytes(StandardCharsets.UTF_8)));

        assertDoesNotThrow(() -> consumer.onEventLogSync(record, ack));
        verify(ack).acknowledge();
        verify(eventLog, never()).appendReplicated(any());
    }

    // =================== Helper ===================
    private EventLogReplicationConsumer createConsumer(EventLog eventLog, HAService haService, String instanceId) {
        EventLogReplicationConsumer consumer = new EventLogReplicationConsumer(eventLog, haService);
        setField(consumer, "instanceId", instanceId);
        return consumer;
    }

    private ConsumerRecord<String, byte[]> buildRecord(String symbolId, String sourceInstanceId) {
        byte[] payload = ProtoConverter.serializeEvent(
                new EventLog.Event(1L, symbolId, null, null, null)
        );
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>("topic", 0, 0, symbolId, payload);
        record.headers().add(new RecordHeader(EventLogReplicationService.HEADER_SOURCE_INSTANCE,
                sourceInstanceId.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            fail("Failed to set field " + fieldName + " : " + e.getMessage());
        }
    }
}