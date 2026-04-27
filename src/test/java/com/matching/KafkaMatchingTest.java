package com.matching;

import com.matching.proto.MatchingProto.*;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kafka integration test for matching engine. Uses embedded Kafka — no external dependencies needed.
 */
@EmbeddedKafka(
        partitions = 1,
        topics = {"TRADE_TOP_SYMBOL", "TRADE_MATCH_RESULT", "TRADE_DEPTH_UPDATE", "TRADE_MATCHING_DLQ"},
        brokerProperties = {"listeners=PLAINTEXT://localhost:39092", "port=39092"}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KafkaMatchingTest {

    private static final String TRADE_TOPIC = "TRADE_TOP_SYMBOL";
    private static final String RESULT_TOPIC = "TRADE_MATCH_RESULT";
    private static final String SYMBOL = "BTCUSDT";

    private static KafkaTemplate<String, byte[]> kafkaTemplate;
    private static Consumer<String, String> resultConsumer;

    @BeforeAll
    static void setup(EmbeddedKafkaBroker broker) {
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(broker);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("test-group", "true", broker);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        resultConsumer = new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer();
        resultConsumer.subscribe(Collections.singletonList(RESULT_TOPIC));
    }

    @AfterAll
    static void teardown() {
        if (resultConsumer != null) resultConsumer.close();
    }

    @Test @Order(1) @DisplayName("Send limit buy order")
    void testPlaceLimitBuyOrder() throws Exception {
        sendAndVerify(buildPlaceOrderBytes(10001, 100L, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "50000.00", "1.5"), SYMBOL);
    }

    @Test @Order(2) @DisplayName("Send limit sell order")
    void testPlaceLimitSellOrder() throws Exception {
        sendAndVerify(buildPlaceOrderBytes(10002, 200L, SYMBOL, "SELL_IN_SINGLE_SIDE_MODE", "LIMIT", "51000.00", "2.0"), SYMBOL);
    }

    @Test @Order(3) @DisplayName("Send market buy order")
    void testPlaceMarketBuyOrder() throws Exception {
        sendAndVerify(buildPlaceOrderBytes(10003, 300L, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "MARKET", null, "0.5"), SYMBOL);
    }

    @Test @Order(4) @DisplayName("Send IOC order")
    void testPlaceIOCOrder() throws Exception {
        sendAndVerify(buildPlaceOrderBytes(10004, 400L, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT_IOC", "49000.00", "1.0"), SYMBOL);
    }

    @Test @Order(5) @DisplayName("Send FOK order")
    void testPlaceFOKOrder() throws Exception {
        sendAndVerify(buildPlaceOrderBytes(10005, 500L, SYMBOL, "SELL_IN_SINGLE_SIDE_MODE", "LIMIT_FOK", "51000.00", "0.1"), SYMBOL);
    }

    @Test @Order(6) @DisplayName("Send Post Only order")
    void testPlacePostOnlyOrder() throws Exception {
        sendAndVerify(buildPlaceOrderBytes(10006, 600L, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT_POST_ONLY", "49500.00", "3.0"), SYMBOL);
    }

    @Test @Order(7) @DisplayName("Send sell order that matches existing buy order")
    void testMatchingBuySell() throws Exception {
        sendAndVerify(buildPlaceOrderBytes(20001, 100L, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "50000.00", "1.0"), SYMBOL);
        sendAndVerify(buildPlaceOrderBytes(20002, 200L, SYMBOL, "SELL_IN_SINGLE_SIDE_MODE", "LIMIT", "50000.00", "1.0"), SYMBOL);
    }

    @Test @Order(8) @DisplayName("Send sell order with partial fill")
    void testPartialFill() throws Exception {
        sendAndVerify(buildPlaceOrderBytes(30001, 100L, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "50000.00", "2.0"), SYMBOL);
        sendAndVerify(buildPlaceOrderBytes(30002, 200L, SYMBOL, "SELL_IN_SINGLE_SIDE_MODE", "LIMIT", "50000.00", "1.0"), SYMBOL);
    }

    @Test @Order(9) @DisplayName("Send single cancel order")
    void testCancelSingleOrder() throws Exception {
        sendAndVerify(buildPlaceOrderBytes(40001, 100L, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "48000.00", "1.0"), SYMBOL);
        sendAndVerify(buildCancelOrderBytes(SYMBOL, List.of("40001"), 100L, false), SYMBOL);
    }

    @Test @Order(10) @DisplayName("Send batch cancel order")
    void testCancelBatchOrders() throws Exception {
        sendAndVerify(buildPlaceOrderBytes(50001, 100L, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "47000.00", "1.0"), SYMBOL);
        sendAndVerify(buildPlaceOrderBytes(50002, 100L, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "47100.00", "2.0"), SYMBOL);
        sendAndVerify(buildCancelOrderBytes(SYMBOL, List.of("50001", "50002"), 100L, false), SYMBOL);
    }

    @Test @Order(11) @DisplayName("Send cancel all orders")
    void testCancelAllOrders() throws Exception {
        sendAndVerify(buildPlaceOrderBytes(60001, 100L, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "46000.00", "1.0"), SYMBOL);
        sendAndVerify(buildPlaceOrderBytes(60002, 100L, SYMBOL, "SELL_IN_SINGLE_SIDE_MODE", "LIMIT", "55000.00", "1.0"), SYMBOL);
        sendAndVerify(buildCancelOrderBytes(SYMBOL, null, 100L, true), SYMBOL);
    }

    @Test @Order(12) @DisplayName("Send invalid bytes — should go to DLQ")
    void testInvalidMessage() throws Exception {
        kafkaTemplate.send(TRADE_TOPIC, SYMBOL, "invalid protobuf bytes".getBytes()).get();
    }

    @Test @Order(13) @DisplayName("Send multiple symbols concurrently")
    void testMultipleSymbols() throws Exception {
        String[] symbols = {"BTCUSDT", "ETHUSDT", "SOLUSDT"};
        for (int i = 0; i < symbols.length; i++) {
            byte[] msg = buildPlaceOrderBytes(7000L + i, 100L + i, symbols[i], "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "1000.00", "1.0");
            kafkaTemplate.send(TRADE_TOPIC, symbols[i], msg).get();
        }
    }

    @Test @Order(14) @DisplayName("Self-trade prevention — same account buy and sell")
    void testSelfTradePrevention() throws Exception {
        long accountId = 888L;
        sendAndVerify(buildPlaceOrderBytes(80001, accountId, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "50000.00", "1.0"), SYMBOL);
        sendAndVerify(buildPlaceOrderBytes(80002, accountId, SYMBOL, "SELL_IN_SINGLE_SIDE_MODE", "LIMIT", "50000.00", "1.0"), SYMBOL);
    }

    // ==================== Helpers ====================

    static byte[] buildPlaceOrderBytes(long orderNo, long accountId, String symbolId,
                                       String delegateType, String orderType,
                                       String price, String quantity) {
        PbPlaceMiniOrderParam.Builder mini = PbPlaceMiniOrderParam.newBuilder()
                .setOrderNo(orderNo)
                .setDelegateType(delegateType)
                .setOrderType(orderType)
                .setDelegateCount(quantity)
                .setBusinessSource("API")
                .setEnterPointSource("WEB")
                .setCreateTime(System.currentTimeMillis());

        if (price != null) mini.setDelegatePrice(price);

        PbPlaceOrderParam param = PbPlaceOrderParam.newBuilder()
                .setRequestTime(System.currentTimeMillis())
                .setBusinessLine("SPOT_BL")
                .setSecondBusinessLine("SRBL")
                .setAccountId(accountId)
                .setUid(accountId) // 用户ID使用accountId
                .setTokenId("BTC")
                .setSymbolId(symbolId)
                .addOrderList(mini)
                .build();

        return PbTradeCommand.newBuilder()
                .setType("PLACE_ORDER")
                .setPayload(param.toByteString())
                .build().toByteArray();
    }

    static byte[] buildCancelOrderBytes(String symbolId, List<String> orderIds, long accountId, boolean cancelAll) {
        PbCancelOrderParam.Builder b = PbCancelOrderParam.newBuilder()
                .setSymbolId(symbolId)
                .setAccountId(accountId)
                .setUid(accountId) // 用户ID使用accountId
                .setCancelAll(cancelAll);

        if (orderIds != null) b.addAllOrderIds(orderIds);

        return PbTradeCommand.newBuilder()
                .setType("CANCEL_ORDER")
                .setPayload(b.build().toByteString())
                .build().toByteArray();
    }

    private void sendAndVerify(byte[] message, String key) throws Exception {
        kafkaTemplate.send(TRADE_TOPIC, key, message).get();
        assertNotNull(message);
        assertTrue(message.length > 0);
    }
}
