package com.matching.monitor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Kafka监听器状态监控 - 确保Kafka监听器在需要时正常运行
 */
@Component
public class KafkaListenerMonitor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KafkaListenerMonitor.class);

    @Autowired
    @Lazy
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    private volatile boolean shouldBeRunning = false;
    private volatile int consecutiveFailures = 0;

    /**
     * 设置Kafka监听器应该运行的状态
     */
    public void setShouldBeRunning(boolean shouldBeRunning) {
        this.shouldBeRunning = shouldBeRunning;
        log.info("Kafka listener should be running: {}", shouldBeRunning);
    }

    /**
     * 定期检查Kafka监听器状态
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 60000) // 每30秒检查一次, 启动后1分钟开始
    public void checkListenerStatus() {
        try {
            boolean isRunning = kafkaListenerEndpointRegistry.isRunning();

            if (shouldBeRunning && !isRunning) {
                log.error("[ALERT] Kafka listener should be running but is stopped. Consecutive failures: {}",
                        consecutiveFailures);
                consecutiveFailures++;
                // 不再自动重启，只记录失败次数
                // 如果连续失败次数过多, 发送严重告警
                if (consecutiveFailures >= 5) {
                    log.error("[CRITICAL] Kafka listener failed to restart {} times consecutively",
                            consecutiveFailures);
                    // TODO: 这里可以集成告警系统, 发送紧急通知
                }
            } else if (!shouldBeRunning && isRunning) {
                log.warn("Kafka listener is running but should be stopped");
            } else {
                // 状态正常, 重置失败计数
                if (consecutiveFailures > 0) {
                    consecutiveFailures = 0;
                    log.info("Kafka listener status normalized");
                }
            }

            // 记录状态用于监控
            log.debug("Kafka listener status check - shouldBeRunning: {}, isRunning: {}",
                    shouldBeRunning, isRunning);
        } catch (Exception e) {
            log.error("Error during Kafka listener status check", e);
        }
    }

    /**
     * 获取当前状态信息
     */
    public KafkaListenerStatus getStatus() {
        boolean isRunning = false;
        try {
            isRunning = kafkaListenerEndpointRegistry.isRunning();
        } catch (Exception e) {
            log.warn("Failed to get Kafka listener running status", e);
        }
        return new KafkaListenerStatus(shouldBeRunning, isRunning, consecutiveFailures);
    }

    /**
     * Kafka监听器状态信息
     */
    public static class KafkaListenerStatus {
        private final boolean shouldBeRunning;
        private final boolean isRunning;
        private final int consecutiveFailures;

        public KafkaListenerStatus(boolean shouldBeRunning, boolean isRunning, int consecutiveFailures) {
            this.shouldBeRunning = shouldBeRunning;
            this.isRunning = isRunning;
            this.consecutiveFailures = consecutiveFailures;
        }

        public boolean isShouldBeRunning() {
            return shouldBeRunning;
        }

        public boolean isRunning() {
            return isRunning;
        }

        public int getConsecutiveFailures() {
            return consecutiveFailures;
        }

        public boolean isHealthy() {
            return shouldBeRunning == isRunning && consecutiveFailures == 0;
        }

        @Override
        public String toString() {
            return String.format("KafkaListenerStatus{shouldBeRunning=%s, isRunning=%s, consecutiveFailures=%d, healthy=%s}",
                    shouldBeRunning, isRunning, consecutiveFailures, isHealthy());
        }
    }
}