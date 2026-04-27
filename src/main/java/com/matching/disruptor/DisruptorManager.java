package com.matching.disruptor;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.matching.command.CancelOrderCommand;
import com.matching.command.PlaceOrderCommand;
import com.matching.dto.CancelOrderParam;
import com.matching.dto.PlaceOrderParam;
import com.matching.enums.TradeCommandType;
import com.matching.config.RedisHealthIndicator;
import com.matching.service.AsyncDepthPublisher;
import com.matching.service.BatchFailureRecoveryService;
import com.matching.service.EventLog;
import com.matching.service.EventLogReplicationService;
import com.matching.service.IdempotentService;
import com.matching.service.MatchingMetrics;
import com.matching.service.OrderBookService;
import com.matching.service.ResultOutboxService;
import com.matching.service.SnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class DisruptorManager {

    @Value("${matching.disruptor.ring-buffer-size:8192}")
    private int ringBufferSize;

    private final PlaceOrderCommand placeOrderCommand;
    private final CancelOrderCommand cancelOrderCommand;
    private final OrderBookService orderBookService;
    private final EventLog eventLog;
    private final AsyncDepthPublisher depthPublisher;
    private final MatchingMetrics metrics;
    private final RedisHealthIndicator redisHealthIndicator;
    private final IdempotentService idempotentService;
    private final ResultOutboxService resultOutboxService;
    private final SnapshotService snapshotService;
    private final BatchFailureRecoveryService batchFailureRecoveryService;
    private final EventLogReplicationService eventLogReplicationService;

    private volatile ResultSplitter resultSplitter;
    private Disruptor<MatchEvent> disruptor;
    private RingBuffer<MatchEvent> ringBuffer;
    private MatchEventHandler handler;

    // Async IO thread pool for replication / Redis / downstream Kafka
    private final ExecutorService asyncIoExecutor = new ThreadPoolExecutor(
            4, 16,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(4096),
            r -> {
                Thread t = new Thread(r, "async-io");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public DisruptorManager(PlaceOrderCommand placeOrderCommand,
                            CancelOrderCommand cancelOrderCommand,
                            OrderBookService orderBookService,
                            EventLog eventLog,
                            AsyncDepthPublisher depthPublisher,
                            MatchingMetrics metrics,
                            RedisHealthIndicator redisHealthIndicator,
                            IdempotentService idempotentService,
                            ResultOutboxService resultOutboxService,
                            SnapshotService snapshotService,
                            BatchFailureRecoveryService batchFailureRecoveryService,
                            EventLogReplicationService eventLogReplicationService) {
        this.placeOrderCommand = placeOrderCommand;
        this.cancelOrderCommand = cancelOrderCommand;
        this.orderBookService = orderBookService;
        this.eventLog = eventLog;
        this.depthPublisher = depthPublisher;
        this.metrics = metrics;
        this.redisHealthIndicator = redisHealthIndicator;
        this.idempotentService = idempotentService;
        this.resultOutboxService = resultOutboxService;
        this.snapshotService = snapshotService;
        this.batchFailureRecoveryService = batchFailureRecoveryService;
        this.eventLogReplicationService = eventLogReplicationService;
    }

    public void setResultSplitter(ResultSplitter resultSplitter) {
        this.resultSplitter = resultSplitter;
        if (handler != null) {
            handler.setResultSplitter(resultSplitter);
        }
    }

    @PostConstruct
    public void init() {
        disruptor = new Disruptor<>(
                MatchEvent::new,
                ringBufferSize,
                r -> {
                    Thread t = new Thread(r, "match-engine");
                    t.setDaemon(false);
                    return t;
                },
                ProducerType.MULTI,
                new BlockingWaitStrategy()
        );

        handler = new MatchEventHandler(
                placeOrderCommand, cancelOrderCommand, orderBookService, eventLog,
                depthPublisher, metrics, idempotentService, resultOutboxService,
                snapshotService, batchFailureRecoveryService, eventLogReplicationService,
                asyncIoExecutor, redisHealthIndicator
        );

        if (resultSplitter != null) {
            handler.setResultSplitter(resultSplitter);
        }

        disruptor.handleEventsWith(handler);

        disruptor.setDefaultExceptionHandler(new ExceptionHandler<MatchEvent>() {
            @Override
            public void handleEventException(Throwable ex, long sequence, MatchEvent event) {
                log.error("Match engine exception on sequence {}", sequence, ex);
            }

            @Override
            public void handleOnStartException(Throwable ex) {
                log.error("Match engine start exception", ex);
            }

            @Override
            public void handleOnShutdownException(Throwable ex) {
                log.error("Match engine shutdown exception", ex);
            }
        });

        disruptor.start();
        ringBuffer = disruptor.getRingBuffer();
        log.info("Single Disruptor started, ringBufferSize: {}", ringBufferSize);
    }

    public void publishPlaceOrder(String symbolId, PlaceOrderParam param, Acknowledgment ack, String traceId) {
        ringBuffer.publishEvent((MatchEvent event, long sequence) -> {
            event.setType(TradeCommandType.PLACE_ORDER);
            event.setSymbolId(symbolId);
            event.setPlaceOrderParam(param);
            event.setAck(ack);
            event.setTraceId(traceId);
        });
    }

    public void publishCancelOrder(String symbolId, CancelOrderParam param, Acknowledgment ack, String traceId) {
        ringBuffer.publishEvent((MatchEvent event, long sequence) -> {
            event.setType(TradeCommandType.CANCEL_ORDER);
            event.setSymbolId(symbolId);
            event.setCancelOrderParam(param);
            event.setAck(ack);
            event.setTraceId(traceId);
        });
    }

    @Scheduled(fixedDelay = 5000)
    public void reportMetrics() {
        if (ringBuffer != null) {
            long remaining = ringBuffer.remainingCapacity();
            metrics.recordRingBufferRemaining("global", remaining);
            if (remaining < ringBufferSize * 0.1) {
                log.warn("RingBuffer nearly full, remaining: {}/{}", remaining, ringBufferSize);
            }
        }
    }

    // Drain is a no-op for single Disruptor without batch buffer. Messages are processed and acked one by one
    public void drainAll() {
        log.info("drainAll called - single Disruptor, no batch buffer to drain");
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down match engine Disruptor");
        if (disruptor != null) {
            try {
                disruptor.shutdown(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("Error shutting down Disruptor", e);
            }
        }

        asyncIoExecutor.shutdown();
        try {
            if (!asyncIoExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncIoExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncIoExecutor.shutdownNow();
        }
    }
}