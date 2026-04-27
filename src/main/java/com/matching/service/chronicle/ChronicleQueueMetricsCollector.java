package com.matching.service.chronicle;

/**
 * Chronicle Queue 指标收集器 负责收集队列相关的各种指标数据
 */
public class ChronicleQueueMetricsCollector {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChronicleQueueMetricsCollector.class);
    private final ChronicleQueueManager queueManager;
    private final ChronicleQueueReader reader;

    // 缓存的指标数据
    private volatile long queueSize = 0;
    private volatile long currentSeq = 0;
    private volatile boolean isPrimary = false;
    private volatile boolean healthy = true;
    private volatile long lastUpdateTime = 0;
    private volatile long lastQueueSizeUpdate = 0;

    private static final long METRICS_CACHE_TTL = 5000; // 5秒缓存
    private static final long QUEUE_SIZE_CACHE_TTL = 5000; // 队列大小缓存

    public ChronicleQueueMetricsCollector(ChronicleQueueManager queueManager,
                                         Object unused, // 兼容参数，忽略
                                         ChronicleQueueReader reader) {
        this.queueManager = queueManager;
        this.reader = reader;
    }

    /**
     * 更新所有指标数据
     */
    public void updateMetrics() {
        long now = System.currentTimeMillis();

        // 使用缓存避免频繁计算
        if (now - lastUpdateTime < METRICS_CACHE_TTL) {
            return;
        }

        try {
            // 更新队列大小
            queueSize = calculateQueueSize();

            // 更新当前序列号
            currentSeq = reader != null ? reader.getMaxSequenceFromQueue() : 0;

            // 更新角色状态
            isPrimary = queueManager != null ? queueManager.isPrimary() : false;

            // 健康检查
            healthy = performHealthCheck();

            lastUpdateTime = now;

            log.debug("Chronicle Queue metrics updated - queueSize: {}, currentSeq: {}, isPrimary: {}, healthy: {}",
                    queueSize, currentSeq, isPrimary, healthy);
        } catch (Exception e) {
            healthy = false;
            log.warn("Failed to update Chronicle Queue metrics", e);
        }
    }

    /**
     * 执行健康检查
     */
    private boolean performHealthCheck() {
        try {
            // 检查队列管理器状态
            if (queueManager.getQueue() == null) {
                return false;
            }

            // 检查是否能正常读取序列号
            reader.getMaxSequenceFromQueue();

            return true;
        } catch (Exception e) {
            log.debug("Chronicle Queue health check failed", e);
            return false;
        }
    }

    /**
     * 计算队列大小 (带缓存优化)
     */
    private long calculateQueueSize() {
        long now = System.currentTimeMillis();

        // 使用缓存避免频繁计算
        if (now - lastQueueSizeUpdate < QUEUE_SIZE_CACHE_TTL) {
            return queueSize;
        }

        if (queueManager == null || queueManager.getQueue() == null) {
            return 0;
        }

        try {
            net.openhft.chronicle.queue.ExcerptTailer sizeTailer = queueManager.getQueue().createTailer();

            // 先尝试快速估算
            long startIndex = sizeTailer.toStart().index();
            long endIndex = sizeTailer.toEnd().index();
            long estimatedSize = endIndex - startIndex;

            // 如果估算值合理，直接使用
            if (estimatedSize >= 0 && estimatedSize < 1000000) {
                lastQueueSizeUpdate = now;
                return estimatedSize;
            }

            // 如果估算值不合理，使用采样方式
            sizeTailer.toStart();
            long count = 0;
            long sampleLimit = 1000; // 最多采样1000条

            while (count < sampleLimit && sizeTailer.readDocument(w -> {})) {
                count++;
            }

            // 如果采样达到上限，估算总数
            long result;
            if (count >= sampleLimit) {
                // 简单的线性估算
                long totalEstimate = count * 2; // 保守估算
                result = Math.min(totalEstimate, 100000); // 设置上限
            } else {
                result = count;
            }

            lastQueueSizeUpdate = now;
            return result;
        } catch (Exception e) {
            log.warn("Failed to calculate queue size, using cached value", e);
            return queueSize; // 返回缓存值
        }
    }

    // Getter methods for metrics
    public long getQueueSize() {
        updateMetrics();
        return queueSize;
    }

    public long getCurrentSeq() {
        updateMetrics();
        return currentSeq;
    }

    public boolean isPrimary() {
        updateMetrics();
        return isPrimary;
    }

    public boolean isHealthy() {
        updateMetrics();
        return healthy;
    }

    public String getRole() {
        return queueManager.getRole();
    }

    /**
     * 获取完整的状态快照
     */
    public MetricsSnapshot getSnapshot() {
        updateMetrics();
        return new MetricsSnapshot(
                getRole(),
                getQueueSize(),
                getCurrentSeq(),
                isPrimary(),
                isHealthy(),
                System.currentTimeMillis()
        );
    }

    /**
     * 指标快照数据类
     */
    public static class MetricsSnapshot {
        private final String role;
        private final long queueSize;
        private final long currentSeq;
        private final boolean isPrimary;
        private final boolean healthy;
        private final long timestamp;

        public MetricsSnapshot(String role, long queueSize, long currentSeq,
                              boolean isPrimary, boolean healthy, long timestamp) {
            this.role = role;
            this.queueSize = queueSize;
            this.currentSeq = currentSeq;
            this.isPrimary = isPrimary;
            this.healthy = healthy;
            this.timestamp = timestamp;
        }

        // Getters
        public String getRole() { return role; }
        public long getQueueSize() { return queueSize; }
        public long getCurrentSeq() { return currentSeq; }
        public boolean isPrimary() { return isPrimary; }
        public boolean isHealthy() { return healthy; }
        public long getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return String.format("MetricsSnapshot{role=%s, queueSize=%d, currentSeq=%d, isPrimary=%s, healthy=%s, timestamp=%d}",
                    role, queueSize, currentSeq, isPrimary, healthy, timestamp);
        }
    }
}