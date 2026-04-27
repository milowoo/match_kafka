package com.matching.service;

import com.matching.config.KafkaTimeoutConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Kafka超时监控服务 - 监控Kafka发送超时情况，提供统计和告警
 */
@Service
@Slf4j
public class KafkaTimeoutMonitorService {

    private final KafkaTimeoutConfig timeoutConfig;

    // 统计计数器
    private final LongAdder totalSendAttempts = new LongAdder();
    private final LongAdder timeoutCount = new LongAdder();
    private final LongAdder successCount = new LongAdder();
    private final AtomicLong lastTimeoutTime = new AtomicLong(0);

    public KafkaTimeoutMonitorService(KafkaTimeoutConfig timeoutConfig) {
        this.timeoutConfig = timeoutConfig;
    }

    /**
     * 记录发送尝试
     */
    public void recordSendAttempt() {
        totalSendAttempts.increment();
    }

    /**
     * 记录超时事件
     */
    public void recordTimeout(String topic, String key, Exception ex) {
        timeoutCount.increment();
        lastTimeoutTime.set(System.currentTimeMillis());

        log.warn("Kafka发送超时 - Topic: {}, Key: {}, 当前超时配置: {}ms, 异常: {}",
                topic, key, timeoutConfig.getSyncSendTimeoutMs(), ex.getMessage());

        // 检查超时率是否过高
        checkTimeoutRate();
    }

    /**
     * 记录成功发送
     */
    public void recordSuccess() {
        successCount.increment();
    }

    /**
     * 记录失败尝试
     */
    public void recordFailedAttempt(String uidKey, Exception ex) {
        timeoutCount.increment();
        lastTimeoutTime.set(System.currentTimeMillis());
        log.warn("Kafka发送失败 - UidKey: {}, 异常: {}", uidKey, ex.getMessage());
    }

    /**
     * 检查超时率
     */
    private void checkTimeoutRate() {
        long total = totalSendAttempts.sum();
        long timeouts = timeoutCount.sum();

        if (total > 100) { // 至少100次尝试后才检查
            double timeoutRate = (double) timeouts / total;

            if (timeoutRate > 0.1) { // 超时率超过10%
                log.error("Kafka超时率过高: {:.2f}% ({}/{}), 建议调整超时配置",
                        timeoutRate * 100, timeouts, total);
            } else if (timeoutRate > 0.05) { // 超时率超过5%
                log.warn("Kafka超时率较高: {:.2f}% ({}/{}), 请关注网络状况",
                        timeoutRate * 100, timeouts, total);
            }
        }
    }

    /**
     * 获取超时统计信息
     */
    public TimeoutStats getTimeoutStats() {
        long total = totalSendAttempts.sum();
        long timeouts = timeoutCount.sum();
        long success = successCount.sum();
        double timeoutRate = total > 0 ? (double) timeouts / total : 0.0;

        return new TimeoutStats(
                total,
                success,
                timeouts,
                timeoutRate,
                lastTimeoutTime.get(),
                timeoutConfig.getSyncSendTimeoutMs(),
                timeoutConfig.getRequestTimeoutMs(),
                timeoutConfig.getDeliveryTimeoutMs()
        );
    }

    /**
     * 重置统计信息
     */
    public void resetStats() {
        totalSendAttempts.reset();
        timeoutCount.reset();
        successCount.reset();
        lastTimeoutTime.set(0);
        log.info("Kafka超时统计信息已重置");
    }

    /**
     * 超时统计信息
     */
    public static class TimeoutStats {
        public final long totalAttempts;
        public final long successCount;
        public final long timeoutCount;
        public final double timeoutRate;
        public final long lastTimeoutTime;
        public final int syncSendTimeoutMs;
        public final int requestTimeoutMs;
        public final int deliveryTimeoutMs;

        public TimeoutStats(long totalAttempts, long successCount, long timeoutCount,
                          double timeoutRate, long lastTimeoutTime,
                          int syncSendTimeoutMs, int requestTimeoutMs, int deliveryTimeoutMs) {
            this.totalAttempts = totalAttempts;
            this.successCount = successCount;
            this.timeoutCount = timeoutCount;
            this.timeoutRate = timeoutRate;
            this.lastTimeoutTime = lastTimeoutTime;
            this.syncSendTimeoutMs = syncSendTimeoutMs;
            this.requestTimeoutMs = requestTimeoutMs;
            this.deliveryTimeoutMs = deliveryTimeoutMs;
        }

        @Override
        public String toString() {
            return String.format("TimeoutStats{attempts=%d, success=%d, timeouts=%d, rate=%.2f%%, " +
                            "lastTimeout=%d, syncTimeout=%dms, requestTimeout=%dms, deliveryTimeout=%dms}",
                    totalAttempts, successCount, timeoutCount, timeoutRate * 100,
                    lastTimeoutTime, syncSendTimeoutMs, requestTimeoutMs, deliveryTimeoutMs);
        }
    }
}