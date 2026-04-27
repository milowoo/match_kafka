package com.matching.performance;

import com.matching.dto.*;
import com.matching.engine.*;
import com.matching.engine.CompactOrderBookEntry;
import com.matching.util.ProtoConverter;
import com.matching.proto.MatchingProto.*;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * 端到端性能基准测试，精确测量生产路径每个阶段的延迟，评估上线TPS
 */
public class EndToEndBenchmarkTest {

    private static final int WARMUP = 20_000;
    private static final int ITERATIONS = 100_000;
    private static final Random RNG = new Random(42);

    // ===================== 1. Protobuf 入口反序列化 =====================
    @Test
    @DisplayName("Stage 1: Protobuf 入口反序列化")
    void benchmarkProtobufDeserialize() throws Exception {
        byte[] payload = buildTypicalPlaceOrderBytes();

        // warmup
        for (int i = 0; i < WARMUP; i++) {
            PbTradeCommand cmd = PbTradeCommand.parseFrom(payload);
            PlaceOrderParam p = ProtoConverter.fromProto(PbPlaceOrderParam.parseFrom(cmd.getPayload()));
        }

        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            PbTradeCommand cmd = PbTradeCommand.parseFrom(payload);
            PlaceOrderParam p = ProtoConverter.fromProto(PbPlaceOrderParam.parseFrom(cmd.getPayload()));
        }
        long elapsed = System.nanoTime() - start;

        printStage("Protobuf 入口反序列化", elapsed, ITERATIONS);
    }

    // ===================== 2. 核心撮合引擎 =====================
    @Test
    @DisplayName("Stage 2: 核心撮合引擎 (addOrder + match)")
    void benchmarkMatchEngine() {
        MatchEngine engine = new MatchEngine("BENCH", 200_000);

        // 先填充对手盘 1000 档
        for (int i = 0; i < 1000; i++) {
            CompactOrderBookEntry sell = makeOrder(i, 2000 + i, 50000000000L + i * 1000000L, 10000000L, CompactOrderBookEntry.SELL);
            engine.addOrder(sell);
        }

        // warmup: 买单吃卖盘
        for (int i = 0; i < WARMUP; i++) {
            CompactOrderBookEntry buy = makeOrder(1000000 + i, 100L, 50000000000L, 1000000L, CompactOrderBookEntry.BUY);
            engine.addOrder(buy);
            // 补充卖盘
            CompactOrderBookEntry refill = makeOrder(2000000 + i, 9000 + i, 50000000000L + RNG.nextInt(1000) * 1000000L, 10000000L, CompactOrderBookEntry.SELL);
            engine.addOrder(refill);
        }

        // 准备测试订单
        CompactOrderBookEntry[] orders = new CompactOrderBookEntry[ITERATIONS];
        CompactOrderBookEntry[] refills = new CompactOrderBookEntry[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            // 买单: 价格略高于最低卖价，确保能成交
            orders[i] = makeOrder(5000000 + i, 100L, 50000000000L + RNG.nextInt(5000), 1000000L, CompactOrderBookEntry.BUY);
            refills[i] = makeOrder(7000000 + i, 9000 + i, 50000000000L + RNG.nextInt(500) * 1000000L, 10000000L, CompactOrderBookEntry.SELL);
        }

        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            engine.addOrder(orders[i]);
            engine.addOrder(refills[i]);
        }
        long elapsed = System.nanoTime() - start;

        printStage("核心撮合引擎 (add+refill)", elapsed, ITERATIONS);
        System.out.printf("订单簿: 买盘 %d 档, 卖盘 %d 档, 总订单 %d%n", engine.getBuyBook().size(), engine.getSellBook().size(), engine.orderCount());
    }

    // ===================== 3. Protobuf 出口序列化 =====================
    @Test
    @DisplayName("Stage 3: Protobuf 出口序列化 (MatchResult -> byte[])")
    void benchmarkProtobufSerialize() throws Exception {
        MatchResult mr = buildTypicalMatchResult();

        // warmup
        for (int i = 0; i < WARMUP; i++) {
            byte[] data = ProtoConverter.serializeMatchResult(mr);
        }

        long start = System.nanoTime();
        long totalBytes = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            byte[] data = ProtoConverter.serializeMatchResult(mr);
            totalBytes += data.length;
        }
        long elapsed = System.nanoTime() - start;

        printStage("Protobuf 出口序列化", elapsed, ITERATIONS);
        System.out.printf("平均消息大小: %d bytes%n", totalBytes / ITERATIONS);
    }

    // ===================== 4. ResultSplitter =====================
    @Test
    @DisplayName("Stage 4: ResultSplitter (拆分+序列化)")
    void benchmarkResultSplitter() {
        // 典型场景: 1笔成交涉及2个账户
        MatchResult mr = buildTypicalMatchResult();

        // warmup
        for (int i = 0; i < WARMUP; i++) {
            splitAndSerialize("BTCUSDT", mr);
        }

        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            Map<String, byte[]> result = splitAndSerialize("BTCUSDT", mr);
        }
        long elapsed = System.nanoTime() - start;

        printStage("ResultSplitter (拆分+序列化)", elapsed, ITERATIONS);
    }

    // ===================== 5. EventLog Protobuf 序列化 =====================
    @Test
    @DisplayName("Stage 5: EventLog Protobuf 序列化")
    void benchmarkEventLogSerialize() throws Exception {
        com.matching.service.EventLog.Event event = buildTypicalEvent();

        // warmup
        for (int i = 0; i < WARMUP; i++) {
            byte[] data = ProtoConverter.serializeEvent(event);
        }

        long start = System.nanoTime();
        long totalBytes = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            byte[] data = ProtoConverter.serializeEvent(event);
            totalBytes += data.length;
        }
        long elapsed = System.nanoTime() - start;

        printStage("EventLog Protobuf 序列化", elapsed, ITERATIONS);
        System.out.printf("平均 Event 大小: %d bytes%n", totalBytes / ITERATIONS);
    }

    // ===================== 6. 深度数据 Protobuf 序列化 =====================
    @Test
    @DisplayName("Stage 6: 深度数据 Protobuf 序列化")
    void benchmarkDepthSerialize() {
        com.matching.model.DepthUpdate update = buildTypicalDepthUpdate();

        // warmup
        for (int i = 0; i < WARMUP; i++) {
            PbDepthUpdate pb = ProtoConverter.convertDepthUpdate(update);
            byte[] data = pb.toByteArray();
        }

        long start = System.nanoTime();
        long totalBytes = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            PbDepthUpdate pb = ProtoConverter.convertDepthUpdate(update);
            byte[] data = pb.toByteArray();
            totalBytes += data.length;
        }
        long elapsed = System.nanoTime() - start;

        printStage("深度数据 Protobuf 序列化", elapsed, ITERATIONS);
        System.out.printf("平均深度信息大小: %d bytes%n", totalBytes / ITERATIONS);
    }

    // ===================== 7. 综合端到端 =====================
    @Test
    @DisplayName("Stage 7: 综合端到端 (Protobuf 解析+撮合+结果序列化)")
    void benchmarkEndToEndNoIO() throws Exception {
        MatchEngine engine = new MatchEngine("E2E", 200_000);
        // 填充卖盘
        for (int i = 0; i < 500; i++) {
            CompactOrderBookEntry sell = makeOrder(i, 2000 + i, 50000000000L + i * 1000000L, 10000000L, CompactOrderBookEntry.SELL);
            engine.addOrder(sell);
        }

        // 构建入口消息
        byte[][] payloads = new byte[ITERATIONS][];
        for (int i = 0; i < ITERATIONS; i++) {
            payloads[i] = buildPlaceOrderBytes(10000 + i, 1000 + (i % 1000), "E2E", "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "50001.00", "0.1");
        }

        // warmup
        for (int i = 0; i < Math.min(WARMUP, ITERATIONS); i++) {
            PbTradeCommand cmd = PbTradeCommand.parseFrom(payloads[i % payloads.length]);
            PlaceOrderParam p = ProtoConverter.fromProto(PbPlaceOrderParam.parseFrom(cmd.getPayload()));
            engine.addOrder(makeOrder(10000 + i, 1000 + (i % 1000), 50000000000L, 1000000L, CompactOrderBookEntry.BUY));
            ProtoConverter.serializeMatchResult(buildTypicalMatchResult());
        }

        MatchResult templateResult = buildTypicalMatchResult();
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            // 1. Protobuf 反序列化
            PbTradeCommand cmd = PbTradeCommand.parseFrom(payloads[i]);
            PlaceOrderParam p = ProtoConverter.fromProto(PbPlaceOrderParam.parseFrom(cmd.getPayload()));
            // 2. 撮合
            engine.addOrder(makeOrder(10000 + i, 1000 + (i % 1000), 50000000000L, 1000000L, CompactOrderBookEntry.BUY));
            // 3. 结果序列化
            ProtoConverter.serializeMatchResult(templateResult);
        }
        long elapsed = System.nanoTime() - start;

        printStage("综合端到端 (不含 Kafka IO)", elapsed, ITERATIONS);
    }

    // ===================== 8. 延迟分布 =====================
    @Test
    @DisplayName("Stage 8: 端到端延迟分布 (P50/P90/P99/P999)")
    void benchmarkLatencyDistribution() throws Exception {
        MatchEngine engine = new MatchEngine("LAT", 200_000);
        for (int i = 0; i < 500; i++) {
            engine.addOrder(makeOrder(i, 2000 + i, 50000000000L + i * 1000000L, 10000000L, CompactOrderBookEntry.SELL));
        }

        byte[][] payloads = new byte[ITERATIONS][];
        for (int i = 0; i < ITERATIONS; i++) {
            payloads[i] = buildPlaceOrderBytes(50000 + i, 1000 + (i % 1000), "LAT", "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "50001.00", "0.1");
        }

        MatchResult templateResult = buildTypicalMatchResult();

        // warmup
        for (int i = 0; i < WARMUP; i++) {
            PbTradeCommand cmd = PbTradeCommand.parseFrom(payloads[i % payloads.length]);
            PlaceOrderParam p = ProtoConverter.fromProto(PbPlaceOrderParam.parseFrom(cmd.getPayload()));
            engine.addOrder(makeOrder(50000 + i, 1000 + (i % 1000), 50000000000L, 1000000L, CompactOrderBookEntry.BUY));
            ProtoConverter.serializeMatchResult(templateResult);
        }

        long[] latencies = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            long t0 = System.nanoTime();
            PbTradeCommand cmd = PbTradeCommand.parseFrom(payloads[i]);
            PlaceOrderParam p = ProtoConverter.fromProto(PbPlaceOrderParam.parseFrom(cmd.getPayload()));
            engine.addOrder(makeOrder(50000 + i, 1000 + (i % 1000), 50000000000L, 1000000L, CompactOrderBookEntry.BUY));
            ProtoConverter.serializeMatchResult(templateResult);
            latencies[i] = System.nanoTime() - t0;
        }

        Arrays.sort(latencies);
        double avg = Arrays.stream(latencies).average().orElse(0);

        System.out.println("\n=== 端到端延迟分布 (不含IO) ===");
        System.out.printf("样本数: %,d%n", ITERATIONS);
        System.out.printf("平均: %.2f µs%n", avg / 1000);
        System.out.printf("P50: %.2f µs%n", latencies[ITERATIONS / 2] / 1000.0);
        System.out.printf("P90: %.2f µs%n", latencies[(int) (ITERATIONS * 0.9)] / 1000.0);
        System.out.printf("P95: %.2f µs%n", latencies[(int) (ITERATIONS * 0.95)] / 1000.0);
        System.out.printf("P99: %.2f µs%n", latencies[(int) (ITERATIONS * 0.99)] / 1000.0);
        System.out.printf("P99.9: %.2f µs%n", latencies[(int) (ITERATIONS * 0.999)] / 1000.0);
        System.out.printf("Max: %.2f µs%n", latencies[ITERATIONS - 1] / 1000.0);
        System.out.printf("TPS: %,.0f%n", ITERATIONS / (Arrays.stream(latencies).sum() / 1_000_000_000.0));
    }

    // ===================== Helpers =====================
    private void printStage(String name, long elapsedNs, int count) {
        double avgNs = (double) elapsedNs / count;
        double tps = count / (elapsedNs / 1_000_000_000.0);
        System.out.printf("\n=== %s ===%n", name);
        System.out.printf("迭代次数: %,d%n", count);
        System.out.printf("总耗时: %.2f ms%n", elapsedNs / 1_000_000.0);
        System.out.printf("平均延迟: %.2f µs%n", avgNs / 1000);
        System.out.printf("TPS: %,.0f%n", tps);
    }

    private CompactOrderBookEntry makeOrder(long orderId, long accountId, long price, long qty, byte side) {
        CompactOrderBookEntry e = new CompactOrderBookEntry();
        e.orderId = orderId;
        e.accountId = accountId;
        e.price = price;
        e.quantity = qty;
        e.remainingQty = qty;
        e.side = side;
        e.requestTime = System.currentTimeMillis();
        return e;
    }

    private MatchResult buildTypicalMatchResult() {
        MatchResult mr = MatchResult.builder().symbolId("BTCUSDT").build();

        MatchDealtResult dealt = new MatchDealtResult();
        dealt.setRecordId(1001L);
        dealt.setOrderId(2001L);
        dealt.setAccountId(100L);
        dealt.setSymbolId("BTCUSDT");
        dealt.setTokenId("USDT");
        dealt.setDealtPrice(new BigDecimal("50000.12345678"));
        dealt.setDealtCount(new BigDecimal("1.23456789"));
        dealt.setDealtAmount(new BigDecimal("61728.30716252"));
        dealt.setTakerMakerFlag("TAKER");
        dealt.setDelegateType("BUY");
        dealt.setOrderType("LIMIT");
        dealt.setCreateTime(new Date());
        mr.getDealtRecords().add(dealt);

        MatchDealtResult makerDealt = new MatchDealtResult();
        makerDealt.setRecordId(1002L);
        makerDealt.setOrderId(2002L);
        makerDealt.setAccountId(200L);
        makerDealt.setSymbolId("BTCUSDT");
        makerDealt.setTokenId("USDT");
        makerDealt.setDealtPrice(new BigDecimal("50000.12345678"));
        makerDealt.setDealtCount(new BigDecimal("1.23456789"));
        makerDealt.setDealtAmount(new BigDecimal("61728.30716252"));
        makerDealt.setTakerMakerFlag("MAKER");
        makerDealt.setDelegateType("SELL");
        makerDealt.setOrderType("LIMIT");
        makerDealt.setCreateTime(new Date());
        mr.getDealtRecords().add(makerDealt);

        MatchOrderResult takerOrder = new MatchOrderResult();
        takerOrder.setOrderId(2001L);
        takerOrder.setAccountId(100L);
        takerOrder.setSymbolId("BTCUSDT");
        takerOrder.setStatus("FILLED");
        takerOrder.setDealtCount(new BigDecimal("1.23456789"));
        takerOrder.setRemainCount(BigDecimal.ZERO);
        takerOrder.setUpdateTime(new Date());
        mr.getDealtOrders().add(takerOrder);

        MatchOrderResult makerOrder = new MatchOrderResult();
        makerOrder.setOrderId(2002L);
        makerOrder.setAccountId(200L);
        makerOrder.setSymbolId("BTCUSDT");
        makerOrder.setStatus("FILLED");
        makerOrder.setDealtCount(new BigDecimal("1.23456789"));
        makerOrder.setRemainCount(BigDecimal.ZERO);
        makerOrder.setUpdateTime(new Date());
        mr.getDealtOrders().add(makerOrder);

        MatchCreateOrderResult co = new MatchCreateOrderResult();
        co.setId(3001L);
        co.setAccountId(100L);
        co.setSymbolId("BTCUSDT");
        co.setStatus("PENDING");
        co.setCancelReason("");
        co.setDealtCount(java.math.BigDecimal.ZERO);
        co.setDealtAmount(java.math.BigDecimal.ZERO);
        mr.getCreateOrders().add(co);

        return mr;
    }

    private com.matching.service.EventLog.Event buildTypicalEvent() {
        List<OrderBookEntry> added = new ArrayList<>();
        added.add(OrderBookEntry.builder()
                .clientOrderId("1001").accountId(100L).symbolId("BTCUSDT").side("BUY")
                .price(new BigDecimal("50000")).quantity(new BigDecimal("1.0")).remainingQuantity(new BigDecimal("1.0")).requestTime(System.currentTimeMillis()).build());

        List<com.matching.service.EventLog.MatchResultEntry> results = new ArrayList<>();
        results.add(new com.matching.service.EventLog.MatchResultEntry("acc-100", ProtoConverter.serializeMatchResult(buildTypicalMatchResult())));

        return new com.matching.service.EventLog.Event(1L, "BTCUSDT", added, List.of("999"), results);
    }

    private com.matching.model.DepthUpdate buildTypicalDepthUpdate() {
        org.eclipse.collections.impl.list.mutable.FastList<com.matching.model.PriceLevel> bids = new org.eclipse.collections.impl.list.mutable.FastList<>();
        org.eclipse.collections.impl.list.mutable.FastList<com.matching.model.PriceLevel> asks = new org.eclipse.collections.impl.list.mutable.FastList<>();
        for (int i = 0; i < 20; i++) {
            bids.add(new com.matching.model.PriceLevel(50000000000L - i * 1000000L, 10000000L));
            asks.add(new com.matching.model.PriceLevel(50001000000L + i * 1000000L, 10000000L));
        }
        return new com.matching.model.DepthUpdate("BTCUSDT", bids, asks, System.currentTimeMillis(), false);
    }

    private Map<String, byte[]> splitAndSerialize(String symbolId, MatchResult matchResult) {
        Map<Long, MatchResult> byAccount = new HashMap<>();
        for (MatchDealtResult dr : matchResult.getDealtRecords()) {
            byAccount.computeIfAbsent(dr.getAccountId(), k -> MatchResult.builder().symbolId(symbolId).build())
                    .getDealtRecords().add(dr);
        }
        for (MatchOrderResult or : matchResult.getDealtOrders()) {
            byAccount.computeIfAbsent(or.getAccountId(), k -> MatchResult.builder().symbolId(symbolId).build())
                    .getDealtOrders().add(or);
        }
        Map<String, byte[]> result = new HashMap<>();
        for (Map.Entry<Long, MatchResult> entry : byAccount.entrySet()) {
            result.put(String.valueOf(entry.getKey()), ProtoConverter.serializeMatchResult(entry.getValue()));
        }
        return result;
    }

    private byte[] buildTypicalPlaceOrderBytes() {
        return buildPlaceOrderBytes(10001, 100, "BTCUSDT", "BUY_IN_SINGLE_SIDE_MODE", "LIMIT", "50000.00", "1.0");
    }

    private byte[] buildPlaceOrderBytes(long orderNo, long accountId, String symbolId,
                                        String delegateType, String orderType,
                                        String price, String quantity) {
        PbPlaceMiniOrderParam.Builder mini = PbPlaceMiniOrderParam.newBuilder()
                .setOrderNo(orderNo).setDelegateCount(quantity).setDelegateType(delegateType).setOrderType(orderType)
                .setCreateTime(System.currentTimeMillis());
        if (price != null) mini.setDelegatePrice(price);

        PbPlaceOrderParam param = PbPlaceOrderParam.newBuilder()
                .setRequestTime(System.currentTimeMillis())
                .setBusinessLine("spot").setAccountId(accountId)
                .setTokenId("USDT").setSymbolId(symbolId)
                .addOrderList(mini.build()).build();

        return PbTradeCommand.newBuilder()
                .setType("PLACE_ORDER").setPayload(param.toByteString()).build().toByteArray();
    }
}