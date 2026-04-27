package com.matching.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EventLog 复制监控指标收集器
 * 用于监控延迟、缓存命中率、主备lag等关键指标
 */
@Component
public class EventLogReplicationMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicLong consumerLag = new AtomicLong(0);
    private final AtomicLong seqMapSize = new AtomicLong(0);

    // 发送延迟计时器
    private final Timer sendLatencyTimer;

    // 缓存miss计数器
    private final Counter cacheMissCounter;

    // 消费者lag指标
    private final Gauge consumerLagGauge;

    // seqMap大小指标
    private final Gauge seqMapSizeGauge;

    public EventLogReplicationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // 初始化主要监控指标
        this.sendLatencyTimer = Timer.builder("eventlog.send.latency")
            .description("EventLog send latency")
            .publishPercentiles(0.5, 0.95, 0.99, 0.999)
            .register(meterRegistry);

        this.cacheMissCounter = Counter.builder("eventlog.cache.miss")
            .description("EventLog cache miss count")
            .register(meterRegistry);

        this.consumerLagGauge = Gauge.builder("eventlog.consumer.lag", consumerLag::get)
            .description("Consumer lag in events")
            .register(meterRegistry);

        this.seqMapSizeGauge = Gauge.builder("eventlog.seqmap.size", seqMapSize::get)
            .description("Seq-to-symbol map size")
            .register(meterRegistry);
    }

    /**
     * 记录发送延迟
     */
    public void recordSendLatency(long latencyMs) {
        sendLatencyTimer.record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录消费者lag
     */
    public void recordConsumerLag(long lag) {
        consumerLag.set(lag);
    }

    /**
     * 记录缓存miss
     */
    public void recordCacheMiss() {
        cacheMissCounter.increment();
    }

    /**
     * 记录seqMap大小
     */
    public void recordSeqMapSize(int size) {
        seqMapSize.set(size);
    }

    /**
     * 记录读取事件操作（用于计算缓存命中率）
     */
    public void recordReadEvent() {
        // 使用Counter记录读取事件总数
        Counter.builder("eventlog.read.event")
            .description("Total event read operations")
            .register(meterRegistry)
            .increment();
    }
}
