package com.matching.service.depth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 性能监控器 - 负责监控和记录性能指标
 */
@Component
@Slf4j
public class PerformanceMonitor {

    @Value("${matching.depth.monitoring.enabled:true}")
    private boolean monitoringEnabled;

    @Value("${matching.depth.queue.capacity:10000}")
    private int queueCapacity;

    /**
     * 启动监控
     */
    public void startMonitoring(ScheduledExecutorService scheduledExecutor,
                                 BackpressureHandler backpressureHandler,
                                 BatchProcessor batchProcessor,
                                 DepthSender depthSender,
                                 BlockingQueue<?> primaryQueue,
                                 BlockingQueue<?> emergencyQueue) {
        if (!monitoringEnabled) {
            return;
        }

        scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                logMetrics(backpressureHandler, batchProcessor, depthSender,
                        primaryQueue, emergencyQueue);
            } catch (Exception e) {
                log.error("监控指标记录异常", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * 记录性能指标
     */
    private void logMetrics(BackpressureHandler backpressureHandler,
                            BatchProcessor batchProcessor,
                            DepthSender depthSender,
                            BlockingQueue<?> primaryQueue,
                            BlockingQueue<?> emergencyQueue) {
        long published = depthSender.getTotalPublished();
        long dropped = backpressureHandler.getTotalDropped();
        long errors = depthSender.getTotalErrors();
        long batches = batchProcessor.getBatchCount();

        int primarySize = primaryQueue.size();
        int emergencySize = emergencyQueue.size();
        boolean backpressure = backpressureHandler.isBackpressureActive();

        log.info("AsyncDepthPublisher 指标 - 已发布: {}, 已丢弃: {}, 错误: {}, " +
                        "批次: {}, 主队列: {}, 紧急队列: {}, 背压: {}",
                published, dropped, errors, batches,
                primarySize, emergencySize, backpressure);

        // 队列使用率警告
        double primaryUsage = (double) primarySize / queueCapacity;
        if (primaryUsage > 0.8) {
            log.warn("主队列使用率过高: {:.1f}%", primaryUsage * 100);
        }

        if (emergencySize > 0) {
            log.warn("紧急队列有积压: {} 条消息", emergencySize);
        }
    }

    /**
     * 获取性能统计
     */
    public PerformanceStats getPerformanceStats(BackpressureHandler backpressureHandler,
                                                BatchProcessor batchProcessor,
                                                DepthSender depthSender,
                                                BlockingQueue<?> primaryQueue,
                                                BlockingQueue<?> emergencyQueue) {
        return new PerformanceStats(
                depthSender.getTotalPublished(),
                backpressureHandler.getTotalDropped(),
                depthSender.getTotalErrors(),
                batchProcessor.getBatchCount(),
                primaryQueue.size(),
                emergencyQueue.size(),
                backpressureHandler.isBackpressureActive()
        );
    }

    /**
     * 性能统计数据类
     */
    public static class PerformanceStats {
        private final long totalPublished;
        private final long totalDropped;
        private final long totalErrors;
        private final long batchCount;
        private final int primaryQueueSize;
        private final int emergencyQueueSize;
        private final boolean backpressureActive;

        public PerformanceStats(long totalPublished, long totalDropped, long totalErrors,
                                long batchCount, int primaryQueueSize, int emergencyQueueSize,
                                boolean backpressureActive) {
            this.totalPublished = totalPublished;
            this.totalDropped = totalDropped;
            this.totalErrors = totalErrors;
            this.batchCount = batchCount;
            this.primaryQueueSize = primaryQueueSize;
            this.emergencyQueueSize = emergencyQueueSize;
            this.backpressureActive = backpressureActive;
        }

        public long getTotalPublished() { return totalPublished; }
        public long getTotalDropped() { return totalDropped; }
        public long getTotalErrors() { return totalErrors; }
        public long getBatchCount() { return batchCount; }
        public int getPrimaryQueueSize() { return primaryQueueSize; }
        public int getEmergencyQueueSize() { return emergencyQueueSize; }
        public boolean isBackpressureActive() { return backpressureActive; }

        @Override
        public String toString() {
            return String.format("PerformanceStats{published=%d, dropped=%d, errors=%d, " +
                            "batches=%d, primaryQueue=%d, emergencyQueue=%d, backpressure=%s}",
                    totalPublished, totalDropped, totalErrors, batchCount,
                    primaryQueueSize, emergencyQueueSize, backpressureActive);
        }
    }
}