package com.matching.disruptor;

import com.matching.command.CancelOrderCommand;
import com.matching.command.PlaceOrderCommand;
import com.matching.config.RedisHealthIndicator;
import com.matching.dto.*;
import com.matching.enums.TradeCommandType;
import com.matching.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SingleDisruptorTest {

    private MatchEventHandler handler;
    private PlaceOrderCommand placeOrderCommand;
    private CancelOrderCommand cancelOrderCommand;
    private OrderBookService orderBookService;
    private EventLog eventLog;
    private MatchingMetrics metrics;
    private IdempotentService idempotentService;
    private ResultOutboxService resultOutboxService;
    private SnapshotService snapshotService;
    private BatchFailureRecoveryService batchFailureRecoveryService;
    private EventLogReplicationService eventLogReplicationService;
    private AsyncDepthPublisher depthPublisher;
    private RedisHealthIndicator redisHealthIndicator;

    @BeforeEach
    void setUp() {
        placeOrderCommand = mock(PlaceOrderCommand.class);
        cancelOrderCommand = mock(CancelOrderCommand.class);
        orderBookService = mock(OrderBookService.class);
        eventLog = mock(EventLog.class);
        metrics = mock(MatchingMetrics.class);
        idempotentService = mock(IdempotentService.class);
        resultOutboxService = mock(ResultOutboxService.class);
        snapshotService = mock(SnapshotService.class);
        batchFailureRecoveryService = mock(BatchFailureRecoveryService.class);
        eventLogReplicationService = mock(EventLogReplicationService.class);
        depthPublisher = mock(AsyncDepthPublisher.class);
        redisHealthIndicator = mock(RedisHealthIndicator.class);
        when(redisHealthIndicator.isHealthy()).thenReturn(true);

        handler = new MatchEventHandler(
                placeOrderCommand, cancelOrderCommand,
                orderBookService, eventLog,
                depthPublisher, metrics, idempotentService, resultOutboxService, snapshotService,
                batchFailureRecoveryService, eventLogReplicationService,
                Executors.newSingleThreadExecutor(), redisHealthIndicator);
    }

    @Test
    void testMultiSymbolRoutesToCorrectOrderBook() {
        when(orderBookService.exists("BTCUSDT")).thenReturn(true);
        when(orderBookService.exists("ETHUSDT")).thenReturn(true);

        when(placeOrderCommand.execute(any())).thenAnswer(invocation -> {
            PlaceOrderParam p = invocation.getArgument(0);
            MatchResult r = MatchResult.builder().symbolId(p.getSymbolId()).build();
            return new CommandResult(r, new SyncPayload(p.getSymbolId(), Collections.emptyMap(), List.of(), List.of()));
        });
        when(eventLog.appendBatch(anyString(), anyList(), anyList(), anyList())).thenReturn(1L);

        Acknowledgment ack1 = mock(Acknowledgment.class);
        Acknowledgment ack2 = mock(Acknowledgment.class);

        MatchEvent btcEvent = new MatchEvent();
        btcEvent.setType(TradeCommandType.PLACE_ORDER);
        btcEvent.setSymbolId("BTCUSDT");
        btcEvent.setPlaceOrderParam(buildPlaceParam("BTCUSDT", 1001));
        btcEvent.setAck(ack1);

        MatchEvent ethEvent = new MatchEvent();
        ethEvent.setType(TradeCommandType.PLACE_ORDER);
        ethEvent.setSymbolId("ETHUSDT");
        ethEvent.setPlaceOrderParam(buildPlaceParam("ETHUSDT", 2001));
        ethEvent.setAck(ack2);

        handler.onEvent(btcEvent, 0, false);
        handler.onEvent(ethEvent, 1, true);

        verify(ack1).acknowledge();
        verify(ack2).acknowledge();
        verify(eventLog).appendBatch(eq("BTCUSDT"), anyList(), anyList(), anyList());
        verify(eventLog).appendBatch(eq("ETHUSDT"), anyList(), anyList(), anyList());
    }

    @Test
    void testEventLogFailureTriggersRecoveryNoAck() {
        when(orderBookService.exists("BTCUSDT")).thenReturn(true);
        MatchResult result = MatchResult.builder().symbolId("BTCUSDT").build();
        when(placeOrderCommand.execute(any()))
                .thenReturn(new CommandResult(result, new SyncPayload("BTCUSDT", Collections.emptyMap(), List.of(), List.of())));
        when(eventLog.appendBatch(anyString(), anyList(), anyList(), anyList()))
                .thenThrow(new RuntimeException("Disk full"));

        Acknowledgment ack = mock(Acknowledgment.class);
        MatchEvent event = new MatchEvent();
        event.setType(TradeCommandType.PLACE_ORDER);
        event.setSymbolId("BTCUSDT");
        event.setPlaceOrderParam(buildPlaceParam("BTCUSDT", 3001));
        event.setAck(ack);

        handler.onEvent(event, 0, true);

        verify(ack, never()).acknowledge();
        verify(batchFailureRecoveryService).performRecovery(eq("BTCUSDT"), anyList(), any());
    }

    @Test
    void testAckImmediatelyAfterEventLogSuccess() {
        when(orderBookService.exists("BTCUSDT")).thenReturn(true);
        MatchResult result = MatchResult.builder().symbolId("BTCUSDT").build();
        when(placeOrderCommand.execute(any()))
                .thenReturn(new CommandResult(result, new SyncPayload("BTCUSDT", Collections.emptyMap(), List.of(), List.of())));
        when(eventLog.appendBatch(anyString(), anyList(), anyList(), anyList())).thenReturn(1L);
        doThrow(new RuntimeException("Kafka down")).when(eventLogReplicationService).replicateEvent(any());

        Acknowledgment ack = mock(Acknowledgment.class);
        MatchEvent event = new MatchEvent();
        event.setType(TradeCommandType.PLACE_ORDER);
        event.setSymbolId("BTCUSDT");
        event.setPlaceOrderParam(buildPlaceParam("BTCUSDT", 4001));
        event.setAck(ack);

        handler.onEvent(event, 0, true);

        verify(ack).acknowledge();
        verify(batchFailureRecoveryService, never()).performRecovery(anyString(), anyList(), any());
    }

    @Test
    void testIdempotentSkipStillAcks() {
        when(orderBookService.exists("BTCUSDT")).thenReturn(true);
        when(idempotentService.isProcessed("BTCUSDT", "5001")).thenReturn(true);

        Acknowledgment ack = mock(Acknowledgment.class);
        MatchEvent event = new MatchEvent();
        event.setType(TradeCommandType.PLACE_ORDER);
        event.setSymbolId("BTCUSDT");
        event.setPlaceOrderParam(buildPlaceParam("BTCUSDT", 5001));
        event.setAck(ack);

        handler.onEvent(event, 0, true);

        verify(ack).acknowledge();
        verify(eventLog, never()).appendBatch(anyString(), anyList(), anyList(), anyList());
    }

    @Test
    void testResultSplitterLateBinding() {
        when(orderBookService.exists("BTCUSDT")).thenReturn(true);
        MatchResult result = MatchResult.builder().symbolId("BTCUSDT").build();
        when(placeOrderCommand.execute(any()))
                .thenReturn(new CommandResult(result, new SyncPayload("BTCUSDT", Collections.emptyMap(), List.of(), List.of())));
        when(eventLog.appendBatch(anyString(), anyList(), anyList(), anyList())).thenReturn(1L);

        Acknowledgment ack1 = mock(Acknowledgment.class);
        MatchEvent event1 = new MatchEvent();
        event1.setType(TradeCommandType.PLACE_ORDER);
        event1.setSymbolId("BTCUSDT");
        event1.setPlaceOrderParam(buildPlaceParam("BTCUSDT", 6001));
        event1.setAck(ack1);
        handler.onEvent(event1, 0, true);
        verify(ack1).acknowledge();

        AtomicInteger splitCount = new AtomicInteger(0);
        handler.setResultSplitter((symbolId, matchResult) -> {
            splitCount.incrementAndGet();
            return Map.of("k1:100", new byte[]{1, 2, 3});
        });

        Acknowledgment ack2 = mock(Acknowledgment.class);
        MatchEvent event2 = new MatchEvent();
        event2.setType(TradeCommandType.PLACE_ORDER);
        event2.setSymbolId("BTCUSDT");
        event2.setPlaceOrderParam(buildPlaceParam("BTCUSDT", 6002));
        event2.setAck(ack2);
        handler.onEvent(event2, 1, true);
        verify(ack2).acknowledge();

        assertTrue(splitCount.get() > 0, "resultSplitter should be called after late binding");
    }

    @Test
    void testSequentialAckOrder() {
        when(orderBookService.exists("BTCUSDT")).thenReturn(true);
        MatchResult result = MatchResult.builder().symbolId("BTCUSDT").build();
        when(placeOrderCommand.execute(any()))
                .thenReturn(new CommandResult(result, new SyncPayload("BTCUSDT", Collections.emptyMap(), List.of(), List.of())));
        when(eventLog.appendBatch(anyString(), anyList(), anyList(), anyList())).thenReturn(1L);

        List<Integer> ackOrder = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            Acknowledgment ack = mock(Acknowledgment.class);
            doAnswer(inv -> { ackOrder.add(idx); return null; }).when(ack).acknowledge();

            MatchEvent event = new MatchEvent();
            event.setType(TradeCommandType.PLACE_ORDER);
            event.setSymbolId("BTCUSDT");
            event.setPlaceOrderParam(buildPlaceParam("BTCUSDT", 7001 + i));
            event.setAck(ack);
            handler.onEvent(event, i, i == 4);
        }

        assertEquals(List.of(0, 1, 2, 3, 4), ackOrder, "Ack order should be sequential");
    }

    private PlaceOrderParam buildPlaceParam(String symbolId, long orderNo) {
        PlaceMiniOrderParam order = new PlaceMiniOrderParam();
        order.setOrderNo(orderNo);

        PlaceOrderParam param = new PlaceOrderParam();
        param.setSymbolId(symbolId);
        param.setUid(12345L); // 添加UID字段
        param.setOrderList(List.of(order));
        return param;
    }
}
