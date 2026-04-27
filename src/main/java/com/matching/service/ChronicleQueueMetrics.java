package com.matching.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chronicle Queue Spring 监控组件 - 负责与Spring框架集成, 注册Micrometer指标和定时任务
 */
@Component
@Slf4j
public class ChronicleQueueMetrics {

    @Autowired
    private UnifiedChronicleQueueEventLog eventLog;

    @Autowired
    private MeterRegistry meterRegistry;

    private volatile boolean metricsRegistered = false;

    /**
     * 每5秒更新一次指标
     */
    @Scheduled(fixedDelay = 5000)
    public void updateMetrics() {
        try {
            // 检查是否初始化完成
            if (!eventLog.isInitialized()) {
                return; // EventLog还未初始化完成
            }

            // 注册Micrometer指标
            if (!metricsRegistered) {
                registerMicrometerMetrics();
                metricsRegistered = true;
            }
        } catch (Exception e) {
            log.warn("Failed to update Chronicle Queue metrics", e);
        }
    }

    /**
     * 获取默认symbol用于指标查询
     */
    private String getDefaultSymbol() {
        return "ETHUSD";
    }

    /**
     * 注册Micrometer指标
     */
    private void registerMicrometerMetrics() {
        try {
            String defaultSymbol = getDefaultSymbol();

            // 队列大小指标
            Gauge.builder("chronicle.queue.size", eventLog,
                    e -> e.getQueueSize(defaultSymbol))
                    .description("Chronicle Queue size")
                    .register(meterRegistry);

            // 当前序列号指标
            Gauge.builder("chronicle.queue.current.seq", eventLog,
                    e -> e.currentSeq(defaultSymbol))
                    .description("Chronicle Queue current sequence number")
                    .register(meterRegistry);

            // 主从状态指标
            Gauge.builder("chronicle.queue.is.primary", eventLog, e -> {
                return "PRIMARY".equals(e.getRole(defaultSymbol)) ? 1.0 : 0.0;
            })
                    .description("Chronicle Queue primary status (1=primary, 0=standby)")
                    .register(meterRegistry);

            // 健康状态指标
            Gauge.builder("chronicle.queue.healthy", eventLog, e -> {
                try {
                    e.currentSeq(defaultSymbol);
                    return 1.0;
                } catch (Exception ex) {
                    return 0.0;
                }
            })
                    .description("Chronicle Queue health status (1=healthy, 0=unhealthy)")
                    .register(meterRegistry);

            log.info("Chronicle Queue Micrometer metrics registered successfully");
        } catch (Exception e) {
            log.warn("Failed to register Chronicle Queue Micrometer metrics", e);
        }
    }

    // Getter methods for metrics (委托给EventLog)
    public long getQueueSize() {
        return eventLog != null ? eventLog.getQueueSize(getDefaultSymbol()) : 0;
    }

    public long getCurrentSeq() {
        return eventLog != null ? eventLog.currentSeq(getDefaultSymbol()) : 0;
    }

    public boolean isPrimary() {
        return eventLog != null && "PRIMARY".equals(eventLog.getRole(getDefaultSymbol()));
    }

    // 健康检查方法
    public boolean isHealthy() {
        if (eventLog == null) {
            return false;
        }
        try {
            eventLog.currentSeq(getDefaultSymbol());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // 获取详细状态信息
    public ChronicleQueueStatus getStatus() {
        if (eventLog == null) {
            return new ChronicleQueueStatus("UNKNOWN", 0, 0, false, System.currentTimeMillis());
        }
        String defaultSymbol = getDefaultSymbol();
        UnifiedChronicleQueueEventLog.MetricsSnapshot snapshot = eventLog.getMetricsSnapshot(defaultSymbol);
        return new ChronicleQueueStatus(
                eventLog.getRole(defaultSymbol),
                eventLog.getQueueSize(defaultSymbol),
                eventLog.currentSeq(defaultSymbol),
                snapshot != null && snapshot.isHealthy(),
                System.currentTimeMillis()
        );
    }

    /**
     * Chronicle Queue状态信息
     */
    public static class ChronicleQueueStatus {
        private final String role;
        private final long queueSize;
        private final long currentSeq;
        private final boolean healthy;
        private final long timestamp;

        public ChronicleQueueStatus(String role, long queueSize, long currentSeq,
                                    boolean healthy, long timestamp) {
            this.role = role;
            this.queueSize = queueSize;
            this.currentSeq = currentSeq;
            this.healthy = healthy;
            this.timestamp = timestamp;
        }

        // Getters
        public String getRole() { return role; }
        public long getQueueSize() { return queueSize; }
        public long getCurrentSeq() { return currentSeq; }
        public boolean isHealthy() { return healthy; }
        public long getTimestamp() { return timestamp; }
    }
}