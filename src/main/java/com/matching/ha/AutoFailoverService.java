package com.matching.ha;

import com.matching.service.HAService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AutoFailoverService - 本地实例监控 职责: 1.内存使用率监控(防OOM) 2.GC健康检查 3.本地实例健康状态
 * 4.告警通知(仅告警模式,不自动切换)
 */
@Service
public class AutoFailoverService {

    private static final Logger logger = LoggerFactory.getLogger(AutoFailoverService.class);

    private final HAService haService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    @Value("${matching.health.monitoring-enabled:true}")
    private boolean monitoringEnabled;

    @Value("${matching.health.alert-only:true}")
    private boolean alertOnlyMode;

    @Value("${matching.health.memory-threshold:0.85}")
    private double memoryThreshold;

    @Value("${matching.health.emergency-threshold:0.95}")
    private double emergencyThreshold;

    @Value("${matching.health.check-interval-ms:1000}")
    private long healthCheckInterval;

    @Value("${matching.ha.heartbeat-timeout-ms:5000}")
    private long heartbeatTimeout;

    private volatile boolean emergencyMode = false;
    private int consecutiveMemoryAlerts = 0;
    private volatile long lastHeartbeat = 0;
    private static final long ALERT_COOLDOWN_MS = 30000;

    public AutoFailoverService(HAService haService) {
        this.haService = haService;
    }

    @PostConstruct
    public void init() {
        if (monitoringEnabled) {
            startHealthMonitoring();
            logger.info("AutoFailoverService已启动 - 本地监控模式, 仅告警: {}, alertOnlyMode");
        } else {
            logger.info("AutoFailoverService已禁用");
        }
    }

    private void startHealthMonitoring() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkMemoryHealth();
                checkGCHealth();
            } catch (Exception e) {
                logger.error("健康检查异常", e);
            }
        }, 0, healthCheckInterval, TimeUnit.MILLISECONDS);
    }

    private void checkMemoryHealth() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        double usedRatio = (double) heapUsage.getUsed() / heapUsage.getMax();

        if (usedRatio > emergencyThreshold) {
            handleCriticalMemoryAlert(usedRatio);
        } else if (usedRatio > memoryThreshold) {
            handleHighMemoryAlert(usedRatio);
        } else {
            if (consecutiveMemoryAlerts > 0) {
                logger.info("内存使用率恢复正常: {:.2f}%", usedRatio * 100);
                consecutiveMemoryAlerts = 0;
                emergencyMode = false;
            }
        }
    }

    private void handleCriticalMemoryAlert(double usedRatio) {
        if (!emergencyMode) {
            emergencyMode = true;
            logger.error("🚨 紧急内存告警: 使用率 {:.2f}% (阈值 {:.2f}%) - 存在OOM风险!",
                    usedRatio * 100, emergencyThreshold * 100);

            if (alertOnlyMode) {
                logger.error("🚨 建议立即执行手动故障转移:");
                logger.error(" 1. POST /ops/ha/deactivate (当前主实例)");
                logger.error(" 2. POST /ops/ha/activate (备实例)");
                logger.error(" 3. 建议运维人员检查内存使用情况并考虑重启服务");
            } else {
                triggerEmergencyFailover("内存使用率危险: " + String.format("%.2f%%", usedRatio * 100));
            }

            // 记录内存详细信息供运维分析, 但不主动触发GC
            logMemoryDetails();
        }
        consecutiveMemoryAlerts++;
    }

    private void handleHighMemoryAlert(double usedRatio) {
        consecutiveMemoryAlerts++;

        long now = System.currentTimeMillis();
        if (now - lastHeartbeat > ALERT_COOLDOWN_MS) {
            logger.warn("⚠️ 高内存使用告警: {:.2f}% (阈值 {:.2f}%) - 连续 {} 次",
                    usedRatio * 100, memoryThreshold * 100, consecutiveMemoryAlerts);
            lastHeartbeat = now;
        }

        if (consecutiveMemoryAlerts >= 5) {
            logger.warn("连续高内存使用 {} 次, 建议运维人员检查内存泄漏或考虑重启服务", consecutiveMemoryAlerts);
            logger.warn("当前内存存储: 使用率 {:.2f}%, 建议通过运维工具进行内存分析", usedRatio * 100);
            consecutiveMemoryAlerts = 0;
        }
    }

    private void checkGCHealth() {
        Runtime runtime = Runtime.getRuntime();
        long freeMemory = runtime.freeMemory();
        long totalMemory = runtime.totalMemory();

        double freeRatio = (double) freeMemory / totalMemory;
        if (freeRatio < 0.05) {
            logger.warn("可用内存不足: {:.2f}%, 建议运维人员检查内存使用情况", freeRatio * 100);
            // 记录详细内存信息供分析, 但不主动GC
            logMemoryDetails();

            // 检查是否需要紧急故障转移
            if (freeRatio < 0.02) {
                triggerEmergencyFailover("可用内存严重不足: " + String.format("%.2f%%", freeRatio * 100));
            }
        }
    }

    private void triggerEmergencyFailover(String reason) {
        if (emergencyMode) {
            return;
        }

        emergencyMode = true;
        logger.error("触发紧急故障转移: {}", reason);

        try {
            if (haService.isPrimary()) {
                logger.info("主实例紧急降级为备实例");
                haService.emergencyDeactivate(reason);
            }
        } catch (Exception e) {
            logger.error("紧急故障转移失败", e);
        }
    }

    public HealthStatus getHealthStatus() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        double memoryUsage = (double) heapUsage.getUsed() / heapUsage.getMax();

        return HealthStatus.builder()
                .memoryUsage(memoryUsage)
                .emergencyMode(emergencyMode)
                .lastHeartbeat(lastHeartbeat)
                .healthy(memoryUsage < memoryThreshold && !emergencyMode)
                .build();
    }

    public static class HealthStatus {
        private double memoryUsage;
        private boolean emergencyMode;
        private long lastHeartbeat;
        private boolean healthy;

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private HealthStatus status = new HealthStatus();

            public Builder memoryUsage(double memoryUsage) {
                status.memoryUsage = memoryUsage;
                return this;
            }

            public Builder emergencyMode(boolean emergencyMode) {
                status.emergencyMode = emergencyMode;
                return this;
            }

            public Builder lastHeartbeat(long lastHeartbeat) {
                status.lastHeartbeat = lastHeartbeat;
                return this;
            }

            public Builder healthy(boolean healthy) {
                status.healthy = healthy;
                return this;
            }

            public HealthStatus build() {
                return status;
            }
        }

        public double getMemoryUsage() {
            return memoryUsage;
        }

        public boolean isEmergencyMode() {
            return emergencyMode;
        }

        public long getLastHeartbeat() {
            return lastHeartbeat;
        }

        public boolean isHealthy() {
            return healthy;
        }
    }

    /**
     * 记录详细内存信息供运维分析
     */
    private void logMemoryDetails() {
        Runtime runtime = Runtime.getRuntime();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();

        logger.warn("=== 内存详细信息 ===");
        logger.warn("堆内存 - 已用: {} MB, 最大: {} MB, 使用率: {:.2f}%",
                heapUsage.getUsed() / 1024 / 1024,
                heapUsage.getMax() / 1024 / 1024,
                (double) heapUsage.getUsed() / heapUsage.getMax() * 100);

        logger.warn("JVM - 总内存: {} MB, 空闲: {} MB, 最大: {} MB",
                runtime.totalMemory() / 1024 / 1024,
                runtime.freeMemory() / 1024 / 1024,
                runtime.maxMemory() / 1024 / 1024);

        logger.warn("建议运维人员使用 jstat, jmap 等工具进行详细分析");
    }
}