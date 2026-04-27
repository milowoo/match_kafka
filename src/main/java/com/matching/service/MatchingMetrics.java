package com.matching.service;

import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class MatchingMetrics {

    private final MeterRegistry registry;

    private final ConcurrentHashMap<String, Counter> matchCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> rejectCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> matchTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> flushTimers = new ConcurrentHashMap<>();

    public MatchingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录撮合成功
     */
    public void recordMatch(String symbolId, long durationNanos) {
        matchCounters.computeIfAbsent(symbolId, s ->
                Counter.builder("matching.order.matched")
                        .tag("symbol", s)
                        .register(registry)
        ).increment();

        matchTimers.computeIfAbsent(symbolId, s ->
                Timer.builder("matching.order.latency")
                        .tag("symbol", s)
                        .register(registry)
        ).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录撮合拒绝
     */
    public void recordReject(String symbolId, String reason) {
        rejectCounters.computeIfAbsent(symbolId + ":" + reason, key ->
                Counter.builder("matching.order.rejected")
                        .tag("symbol", symbolId)
                        .tag("reason", reason)
                        .register(registry)
        ).increment();
    }

    /**
     * 记录刷盘操作
     */
    public void recordFlush(String symbolId, long durationNanos, int batchSize) {
        flushTimers.computeIfAbsent(symbolId, s ->
                Timer.builder("matching.flush.latency")
                        .tag("symbol", s)
                        .register(registry)
        ).record(durationNanos, TimeUnit.NANOSECONDS);

        registry.gauge("matching.flush.batch_size", Tags.of("symbol", symbolId), batchSize);
    }

    /**
     * 记录订单簿大小
     */
    public void recordOrderBookSize(String symbolId, int buyLevels, int sellLevels, int totalOrders) {
        registry.gauge("matching.orderbook.buy_levels", Tags.of("symbol", symbolId), buyLevels);
        registry.gauge("matching.orderbook.sell_levels", Tags.of("symbol", symbolId), sellLevels);
        registry.gauge("matching.orderbook.total_orders", Tags.of("symbol", symbolId), totalOrders);
    }

    /**
     * 记录RingBuffer剩余容量
     */
    public void recordRingBufferRemaining(String symbolId, long remaining) {
        registry.gauge("matching.disruptor.remaining_capacity", Tags.of("symbol", symbolId), remaining);
    }

    /**
     * 记录批处理失败恢复成功
     */
    public void recordBatchFailureRecovery(String symbolId, int failedOrderCount) {
        Counter.builder("matching.batch.failure_recovery")
                .tag("symbol", symbolId)
                .tag("status", "success")
                .register(registry)
                .increment();

        registry.gauge("matching.batch.recovered_orders", Tags.of("symbol", symbolId), failedOrderCount);
        log.info("[Metrics] Recorded batch failure recovery success for symbol: {}, orders: {}",
                symbolId, failedOrderCount);
    }

    /**
     * 记录批处理失败恢复失败
     */
    public void recordBatchFailureRecoveryFailed(String symbolId, int failedOrderCount) {
        Counter.builder("matching.batch.failure_recovery")
                .tag("symbol", symbolId)
                .tag("status", "failed")
                .register(registry)
                .increment();

        registry.gauge("matching.batch.recovery_failed_orders", Tags.of("symbol", symbolId), failedOrderCount);
        log.warn("[Metrics] Recorded batch failure recovery failure for symbol: {}, orders: {}",
                symbolId, failedOrderCount);
    }

    /**
     * 记录幂等标记清理操作
     */
    public void recordIdempotentMarkClearing(String symbolId, int orderCount, String clearType) {
        Counter.builder("matching.idempotent.mark_clearing")
                .tag("symbol", symbolId)
                .tag("type", clearType) // "local", "redis", "complete"
                .register(registry)
                .increment();

        registry.gauge("matching.idempotent.cleared_marks",
                Tags.of("symbol", symbolId, "type", clearType), orderCount);
    }

    /**
     * 记录幂等性检查统计
     */
    public void recordIdempotentCheck(String symbolId, boolean isDuplicate, String checkLevel) {
        Counter.builder("matching.idempotent.check")
                .tag("symbol", symbolId)
                .tag("result", isDuplicate ? "duplicate" : "new")
                .tag("level", checkLevel) // "local", "redis"
                .register(registry)
                .increment();
    }
}