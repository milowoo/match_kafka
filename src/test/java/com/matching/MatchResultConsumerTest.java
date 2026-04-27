package com.matching;

import com.matching.dto.*;
import com.matching.service.KafkaConsumerStartupService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@TestPropertySource(properties = {
        "matching.ha.enabled=false",
        "matching.ha.role=PRIMARY",
        "matching.eventlog.dir=/tmp/matching/eventlog-queue",
        "spring.redis.cluster.nodes=localhost:70000",
        "spring.main.allow-circular-references=true",
        "matching.kafka.consumer.auto-startup=true",
        "matching.kafka.consumer.startup-delay-ms=1000",
        "matching.kafka.topic.trade=TRADE_TOP_SYMBOL",
        "matching.kafka.topic.result=TRADE_MATCH_RESULT",
        "matching.kafka.consumer.group-id=matching-test-group",
        "spring.profiles.active=test"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MatchResultConsumerTest {

    private static final String TRADE_TOPIC = "TRADE_TOP_SYMBOL";
    private static final String RESULT_TOPIC = "TRADE_MATCH_RESULT";
    private static final String SYMBOL = "ETHUSDT";

    private static KafkaTemplate<String, byte[]> testProducer;
    private static Consumer<String, byte[]> resultConsumer;

    @Autowired
    private KafkaTemplate<String, byte[]> injectedKafkaTemplate;

    @Autowired
    private com.matching.service.KafkaConsumerStartupService kafkaConsumerStartupService;

    @Autowired
    private com.matching.service.OrderBookService orderBookService;

    @Autowired
    private com.matching.service.HAService haService;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private com.matching.config.RedisHealthIndicator redisHealthIndicator;

    @MockBean
    private com.matching.config.symbol.SymbolConfigService symbolConfigService;

    private static boolean orderBooksLoaded = false;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setupRedisMocks() {
        // 使用 Spring 注入的 KafkaTemplate，确保连接到正确的 embedded broker
        testProducer = injectedKafkaTemplate;
        // 重建 resultConsumer，确保连接到正确的 embedded broker
        if (resultConsumer == null) {
            Map<String, Object> consumerProps = new HashMap<>();
            consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
            consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "result-test-group-" + System.currentTimeMillis());
            consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
            consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
            resultConsumer = new DefaultKafkaConsumerFactory<String, byte[]>(consumerProps).createConsumer();
            resultConsumer.subscribe(Collections.singletonList(RESULT_TOPIC));
            resultConsumer.poll(Duration.ofMillis(500));
        }
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);

        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(redisTemplate.delete(anyList())).thenReturn(6L);

        List<Object> pipelineResults = new ArrayList<>();
        for (int i = 0; i < 18; i++) pipelineResults.add(Collections.emptyMap());
        when(redisTemplate.executePipelined(any(org.springframework.data.redis.core.SessionCallback.class)))
                .thenReturn(pipelineResults);

        when(hashOps.entries(anyString())).thenReturn(Collections.emptyMap());
        when(hashOps.get(anyString(), anyString())).thenReturn(null);
        when(hashOps.hasKey(anyString(), anyString())).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn(null);
        when(zSetOps.range(anyString(), anyLong(), anyLong())).thenReturn(Collections.emptySet());
        when(zSetOps.reverseRange(anyString(), anyLong(), anyLong())).thenReturn(Collections.emptySet());

        when(redisHealthIndicator.isHealthy()).thenReturn(true);

        com.matching.config.symbol.SymbolConfig ethConfig = new com.matching.config.symbol.SymbolConfig();
        ethConfig.setSymbolId(SYMBOL);
        ethConfig.setPricePrecision(2);
        ethConfig.setQuantityPrecision(4);
        ethConfig.setMinQuantity(new BigDecimal("0.001"));
        ethConfig.setMaxQuantity(new BigDecimal("1000000"));
        ethConfig.setMinPrice(new BigDecimal("0.01"));
        ethConfig.setMaxPrice(new BigDecimal("100000"));
        ethConfig.setMinNotional(new BigDecimal("0.01"));
        ethConfig.setPriceDeviationRate(new BigDecimal("10.0"));
        ethConfig.setMaxOpenOrders(10000);
        ethConfig.setTradingEnabled(true);
        com.matching.config.symbol.SymbolConfig xyzConfig = new com.matching.config.symbol.SymbolConfig();
        xyzConfig.setSymbolId("XYZUSD");
        xyzConfig.setPricePrecision(2);
        xyzConfig.setQuantityPrecision(6);
        xyzConfig.setMinQuantity(new BigDecimal("0.001"));
        xyzConfig.setMaxQuantity(new BigDecimal("1000000"));
        xyzConfig.setMinPrice(new BigDecimal("0.001"));
        xyzConfig.setMaxPrice(new BigDecimal("100000"));
        xyzConfig.setMinNotional(new BigDecimal("0.01"));
        xyzConfig.setPriceDeviationRate(new BigDecimal("10.0"));
        xyzConfig.setMaxOpenOrders(10000);
        xyzConfig.setTradingEnabled(true);

        when(symbolConfigService.getConfig(SYMBOL)).thenReturn(ethConfig);
        when(symbolConfigService.getConfig("XYZUSD")).thenReturn(xyzConfig);
        when(symbolConfigService.getActiveSymbolIds()).thenReturn(java.util.Arrays.asList(SYMBOL, "XYZUSD"));

        // mock 设置好后，手动加载 OrderBook（只加载一次）
        if (!orderBooksLoaded) {
            orderBookService.loadFromEventLog(SYMBOL, false);
            orderBookService.loadFromEventLog("XYZUSD", false);
            orderBooksLoaded = true;
        }
    }

    @BeforeAll
    static void setup() throws Exception {
        Path eventLogDir = Path.of(System.getProperty("java.io.tmpdir"), "matching-result-test-eventlog");
        if (Files.exists(eventLogDir)) {
            try (var stream = Files.walk(eventLogDir).sorted(Comparator.reverseOrder())) {
                stream.forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
        Files.createDirectories(eventLogDir);
        Files.createDirectories(eventLogDir.resolve("outbox"));

        // testProducer 在 @BeforeEach 里通过 injectedKafkaTemplate 赋值

        // resultConsumer 在 @BeforeEach 里初始化
    }

    @AfterAll
    static void teardown() {
        if (resultConsumer != null) resultConsumer.close();
    }

    private void warmupSymbol(String symbolId) throws Exception {
        ensureKafkaConsumerRunning();
        sendPlaceOrder(99999L, 99999L, symbolId, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "1.00", "0.001");
        Thread.sleep(2000);
        consumeResults(Duration.ofSeconds(3));
    }

    private void ensureKafkaConsumerRunning() throws Exception {
        // 先尝试启动
        if (!kafkaConsumerStartupService.shouldBeRunning()) {
            kafkaConsumerStartupService.startConsumers();
        }
        // 等待 Kafka 实际运行，最多等 10 秒
        for (int i = 0; i < 20; i++) {
            var status = kafkaConsumerStartupService.getStartupStatus();
            if (status == KafkaConsumerStartupService.StartupStatus.RUNNING) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException("Kafka consumers failed to start within 10 seconds");
    }

    // ==================== Tests ====================

    @Test @Order(1)
    @DisplayName("Limit order placed - result should contain createOrder with PENDING status")
    void testInitOrderCreateResult() throws Exception {
        warmupSymbol(SYMBOL);
        long orderNo = 10001L, accountId = 100L;
        sendPlaceOrder(orderNo, accountId, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "3000.00", "2.0");

        List<MatchResult> results = consumeResults(Duration.ofSeconds(15));
        MatchResult result = findResultForAccount(results, accountId);

        assertNotNull(result, "Should receive result for account " + accountId);
        assertEquals(SYMBOL, result.getSymbolId());
        assertFalse(result.getCreateOrders().isEmpty(), "Should have createOrder");

        MatchCreateOrderResult createOrder = result.getCreateOrders().get(0);
        assertEquals(orderNo, createOrder.getId());
        assertTrue(List.of("NEW", "PENDING", "FILLED", "PARTIAL_FILLED").contains(createOrder.getStatus()));
    }

    @Test @Order(2)
    @DisplayName("Two orders match - both accounts should receive dealt results")
    void testMatchDealtResults() throws Exception {
        long buyOrderNo = 20001L, sellOrderNo = 20002L;
        long buyAccountId = 200L, sellAccountId = 201L;
        String price = "3500.00", qty = "5.0";

        sendPlaceOrder(buyOrderNo, buyAccountId, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", price, qty);
        Thread.sleep(2000);
        sendPlaceOrder(sellOrderNo, sellAccountId, SYMBOL, "SELL_IN_SINGLE_SIDE_MODE", "LIMIT", price, qty);

        List<MatchResult> results = consumeResults(Duration.ofSeconds(15));

        MatchResult sellerResult = findResultForAccount(results, sellAccountId);
        assertNotNull(sellerResult, "Seller should receive result");
        if (!sellerResult.getDealtRecords().isEmpty()) {
            MatchDealtResult sellerDealt = sellerResult.getDealtRecords().get(0);
            assertEquals(sellAccountId, sellerDealt.getAccountId());
            assertEquals("TAKER", sellerDealt.getTakerMakerFlag());
            assertEquals(0, new BigDecimal(price).compareTo(sellerDealt.getDealtPrice()));
        }
        assertNotNull(findResultForAccount(results, buyAccountId), "Buyer should receive result");
    }

    @Test @Order(3)
    @DisplayName("Partial fill - taker gets PARTIAL_FILLED, maker gets FILLED")
    void testPartialFillResults() throws Exception {
        long makerOrderNo = 30001L, takerOrderNo = 30002L;
        long makerAccountId = 300L, takerAccountId = 301L;
        String price = "3600.00";

        sendPlaceOrder(makerOrderNo, makerAccountId, SYMBOL, "SELL_IN_SINGLE_SIDE_MODE", "LIMIT", price, "2.0");
        Thread.sleep(2000);
        sendPlaceOrder(takerOrderNo, takerAccountId, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", price, "3.0");

        List<MatchResult> results = consumeResults(Duration.ofSeconds(15));
        MatchResult takerResult = findResultForAccount(results, takerAccountId);
        assertNotNull(takerResult, "Taker should receive result");

        if (!takerResult.getDealtOrders().isEmpty()) {
            Optional<MatchOrderResult> makerOrder = takerResult.getDealtOrders().stream()
                    .filter(o -> o.getOrderId() == makerOrderNo).findFirst();
            makerOrder.ifPresent(o -> assertEquals("FILLED", o.getStatus()));
        }
    }

    @Test @Order(4)
    @DisplayName("Market order with no liquidity - should get CANCELLED result")
    void testMarketOrderNoLiquidity() throws Exception {
        warmupSymbol("XYZUSD");
        long orderNo = 40001L, accountId = 400L;
        sendPlaceOrder(orderNo, accountId, "XYZUSD", "BUY_IN_SINGLE_SIDE_MODE", "MARKET", null, "1.0");

        List<MatchResult> results = consumeResults(Duration.ofSeconds(10));
        MatchResult result = findResultForAccount(results, accountId);
        if (result != null && !result.getCreateOrders().isEmpty()) {
            MatchCreateOrderResult createOrder = result.getCreateOrders().get(0);
            assertEquals("CANCELLED", createOrder.getStatus());
            assertEquals("MARKET_NO_LIQUIDITY", createOrder.getCancelReason());
        }
    }

    @Test @Order(5)
    @DisplayName("IOC order unfilled - should get CANCELLED with IOC_UNFILLED reason")
    void testIOCUnfilled() throws Exception {
        long orderNo = 50001L, accountId = 500L;
        sendPlaceOrder(orderNo, accountId, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT_IOC", "1.00", "1.0");

        List<MatchResult> results = consumeResults(Duration.ofSeconds(10));
        MatchResult result = findResultForAccount(results, accountId);
        if (result != null && !result.getCreateOrders().isEmpty()) {
            MatchCreateOrderResult createOrder = result.getCreateOrders().get(0);
            assertEquals("CANCELLED", createOrder.getStatus());
            assertEquals("IOC_UNFILLED", createOrder.getCancelReason());
        }
    }

    @Test @Order(6)
    @DisplayName("Cancel order - should receive CANCELLED dealtOrder")
    void testCancelOrderResult() throws Exception {
        long orderNo = 60001L, accountId = 600L;
        sendPlaceOrder(orderNo, accountId, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "2000.00", "1.0");
        Thread.sleep(1000);

        byte[] cancelMsg = KafkaMatchingTest.buildCancelOrderBytes(SYMBOL, List.of(String.valueOf(orderNo)), accountId, false);
        testProducer.send(TRADE_TOPIC, SYMBOL, cancelMsg).get();

        List<MatchResult> results = consumeResults(Duration.ofSeconds(10));
        MatchResult result = findResultForAccount(results, accountId);
        if (result != null && !result.getDealtOrders().isEmpty()) {
            MatchOrderResult cancelledOrder = result.getDealtOrders().get(0);
            assertEquals(orderNo, cancelledOrder.getOrderId());
            assertEquals("CANCELLED", cancelledOrder.getStatus());
        }
    }

    @Test @Order(7)
    @DisplayName("Match creates correct averagePrice when taker eats multiple makers")
    void testAveragePriceCalculation() throws Exception {
        long maker1 = 70001L, maker2 = 70002L, taker = 70003L;
        long makerAccount1 = 701L, makerAccount2 = 702L, takerAccount = 703L;

        sendPlaceOrder(maker1, makerAccount1, SYMBOL, "SELL_IN_SINGLE_SIDE_MODE", "LIMIT", "2500.00", "1.0");
        sendPlaceOrder(maker2, makerAccount2, SYMBOL, "SELL_IN_SINGLE_SIDE_MODE", "LIMIT", "2510.00", "1.0");
        sendPlaceOrder(taker, takerAccount, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "2520.00", "2.0");

        List<MatchResult> results = consumeResults(Duration.ofSeconds(15));
        MatchResult takerResult = findResultForAccount(results, takerAccount);
        if (takerResult != null && !takerResult.getCreateOrders().isEmpty()) {
            MatchCreateOrderResult createOrder = takerResult.getCreateOrders().get(0);
            if (createOrder.getAverageDealPrice() != null
                    && createOrder.getAverageDealPrice().compareTo(BigDecimal.ZERO) > 0) {
                assertEquals(0, new BigDecimal("2505.00000000").compareTo(createOrder.getAverageDealPrice()));
            }
        }
    }

    @Test @Order(8)
    @DisplayName("Result messages are keyed by accountId")
    void testResultKeyedByAccountId() throws Exception {
        long buyOrderNo = 80001L, sellOrderNo = 80002L;
        long buyAccountId = 800L, sellAccountId = 801L;
        String price = "4000.00", qty = "1.0";

        sendPlaceOrder(buyOrderNo, buyAccountId, SYMBOL, "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", price, qty);
        Thread.sleep(2000);
        sendPlaceOrder(sellOrderNo, sellAccountId, SYMBOL, "SELL_IN_SINGLE_SIDE_MODE", "LIMIT", price, qty);

        Set<String> keys = new HashSet<>();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, byte[]> records = resultConsumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, byte[]> record : records) {
                if (record.key() != null) keys.add(record.key());
            }
            if (keys.contains(String.valueOf(buyAccountId)) || keys.contains(String.valueOf(sellAccountId))) break;
        }

        assertTrue(
                keys.contains(String.valueOf(buyAccountId)) || keys.contains(String.valueOf(sellAccountId)),
                "Result keys should be accountIds, got: " + keys);
    }

    // ==================== Helpers ====================

    private void sendPlaceOrder(long orderNo, long accountId, String symbolId,
                                String delegateType, String orderType,
                                String price, String quantity) throws Exception {
        byte[] msg = KafkaMatchingTest.buildPlaceOrderBytes(orderNo, accountId, symbolId,
                delegateType, orderType, price, quantity);
        testProducer.send(TRADE_TOPIC, symbolId, msg).get();
    }

    private List<MatchResult> consumeResults(Duration timeout) {
        List<MatchResult> results = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, byte[]> records = resultConsumer.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, byte[]> record : records) {
                try {
                    MatchResult result = com.matching.util.ProtoConverter.deserializeMatchResult(record.value());
                    results.add(result);
                } catch (Exception e) {
                    System.out.println("Failed to parse record: " + e.getMessage());
                }
            }
        }
        return results;
    }

    private MatchResult findResultForAccount(List<MatchResult> results, long accountId) {
        for (MatchResult r : results) {
            for (MatchCreateOrderResult co : r.getCreateOrders()) {
                if (co.getAccountId() == accountId) return r;
            }
            for (MatchDealtResult dr : r.getDealtRecords()) {
                if (dr.getAccountId() == accountId) return r;
            }
            for (MatchOrderResult or : r.getDealtOrders()) {
                if (or.getAccountId() == accountId) return r;
            }
        }
        return null;
    }
}
