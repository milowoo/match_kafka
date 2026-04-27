package com.matching.ha;

import com.matching.service.HAService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 统一健康监控服务 - 整合所有监控功能
 * 功能: 1.内存使用率监控(防OOM) 2.GC健康检查 3.对端实例健康检查
 * (HTTP) 4. 告警通知 (仅告警, 不自动切换)
 */
@Slf4j
@Service
public class UnifiedHealthMonitorService {

    private final HAService haService;
    private final InstanceLeaderElection leaderElection;
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    // 基础配置
    @Value("${matching.ha.enabled:true}")
    private boolean haEnabled;

    @Value("${matching.health.monitoring-enabled:true}")
    private boolean monitoringEnabled;

    @Value("${matching.health.alert-only:true}")
    private boolean alertOnlyMode;

    // 内存监控配置
    @Value("${matching.health.memory-threshold:0.85}")
    private double memoryThreshold;

    @Value("${matching.health.emergency-threshold:0.95}")
    private double emergencyThreshold;

    // 对端监控配置
    @Value("${matching.ha.peer-url:}")
    private String peerUrl;

    @Value("${matching.ha.failover-threshold:3}")
    private int failoverThreshold;

    //@Value("${matching.ha.health-check-timeout-ms:2000}")
    private int healthCheckTimeoutMs;

    // 状态变量
    private int consecutivePeerFailures = 0;
    private int consecutiveMemoryAlerts = 0;
    private volatile boolean criticalMemoryMode = false;
    private volatile long lastAlertTime = 0;
    private static final long ALERT_COOLDOWN_MS = 30000; // 30秒告警冷却

    public UnifiedHealthMonitorService(HAService haService, InstanceLeaderElection leaderElection) {
        this.haService = haService;
        this.leaderElection = leaderElection;
    }

    @PostConstruct
    public void init() {
        if (monitoringEnabled) {
            log.info("统一健康监控服务已启动 - HA: {}, 仅告警模式: {}", haEnabled, alertOnlyMode);
        } else {
            log.info("健康监控服务已禁用");
        }
    }

    /**
     * 内存健康监控 - 每秒检查
     */
    @Scheduled(fixedDelayString = "1000")
    public void checkMemoryHealth() {
        if (!monitoringEnabled) return;

        try {
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            double usedRatio = (double) heapUsage.getUsed() / heapUsage.getMax();

            // 检查内存使用率
            if (usedRatio > emergencyThreshold) {
                // 紧急内存告警
                handleCriticalMemoryAlert(usedRatio);
            } else if (usedRatio > memoryThreshold) {
                // 高内存使用告警
                handleHighMemoryAlert(usedRatio);
            } else {
                // 内存正常, 重置计数器
                if (consecutiveMemoryAlerts > 0) {
                    log.info("内存使用率恢复正常: {:.2f}%", usedRatio * 100);
                    consecutiveMemoryAlerts = 0;
                    criticalMemoryMode = false;
                }
            }
        } catch (Exception e) {
            log.error("内存健康检查异常", e);
        }
    }

    /**
     * 对端实例健康监控 - 每2秒检查
     */
    @Scheduled(fixedDelayString = "1000")
    public void checkPeerHealth() {
        if (!monitoringEnabled || !haEnabled || peerUrl.isEmpty()) return;

        // 只有备实例才检查主实例
        if (leaderElection.getState() != InstanceLeaderElection.State.STANDBY) {
            consecutivePeerFailures = 0;
            return;
        }

        try {
            boolean peerActive = checkPeerIsActive(peerUrl);

            if (peerActive) {
                if (consecutivePeerFailures > 0) {
                    log.info("对端实例恢复正常, 重置失败计数");
                    consecutivePeerFailures = 0;
                }
            } else {
                consecutivePeerFailures++;
                log.warn("对端实例健康检查失败 ({}/{})", consecutivePeerFailures, failoverThreshold);

                if (consecutivePeerFailures >= failoverThreshold) {
                    handlePeerFailureAlert();
                }
            }
        } catch (Exception e) {
            log.error("对端健康检查异常", e);
        }
    }

    /**
     * 处理紧急内存告警
     */
    private void handleCriticalMemoryAlert(double usedRatio) {
        if (!criticalMemoryMode) {
            criticalMemoryMode = true;
            log.error("🚨 紧急内存告警: 使用率 {:.2f}% (阈值 {:.2f}%) - 存在OOM风险!",
                    usedRatio * 100, emergencyThreshold * 100);

            if (alertOnlyMode) {
                log.error("🚨 建议立即执行手动故障转移:");
                log.error(" 1. POST /ops/ha/deactivate (当前主实例)");
                log.error(" 2. POST /ops/ha/activate (备实例)");
                log.error(" 3. 建议运维人员检查内存使用情况并考虑重启服务");
            } else {
                // 触发紧急故障转移逻辑
                triggerEmergencyFailover("内存使用率危险: " + String.format("%.2f%%", usedRatio * 100));
            }

            // 记录内存详细信息供运维分析, 但不主动触发GC
            logMemoryDetails();
        }
        consecutiveMemoryAlerts++;
    }

    /**
     * 处理高内存使用告警
     */
    private void handleHighMemoryAlert(double usedRatio) {
        consecutiveMemoryAlerts++;

        // 避免频繁告警
        long now = System.currentTimeMillis();
        if (now - lastAlertTime > ALERT_COOLDOWN_MS) {
            log.warn("⚠️ 高内存使用告警: {:.2f}% (阈值 {:.2f}%) - 连续 {} 次",
                    usedRatio * 100, memoryThreshold * 100, consecutiveMemoryAlerts);
            lastAlertTime = now;
        }

        // 连续高内存使用时建议运维人员处理
        if (consecutiveMemoryAlerts >= 5) {
            log.warn("连续高内存使用 {} 次, 建议运维人员检查内存泄漏或考虑重启服务", consecutiveMemoryAlerts);
            log.warn("当前内存详情: 使用率 {:.2f}%, 建议通过运维工具进行内存分析", usedRatio * 100);
            consecutiveMemoryAlerts = 0; // 重置计数
        }
    }

    /**
     * 处理对端失败告警
     */
    private void handlePeerFailureAlert() {
        log.error("🚨 对端实例故障告警: 连续 {} 次检查失败!", consecutivePeerFailures);
        log.error("🚨 建议执行手动故障转移:");
        log.error(" POST /ops/ha/activate (当前备实例)");

        // 重置计数避免刷屏
        consecutivePeerFailures = 0;
    }

    /**
     * 检查对端实例是否活跃
     */
    private boolean checkPeerIsActive(String baseUrl) {
        try {
            URL url = new URL(baseUrl + "/ops/ha/status");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(healthCheckTimeoutMs);
            conn.setReadTimeout(healthCheckTimeoutMs);

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return false;
            }

            String body = new String(conn.getInputStream().readAllBytes());
            conn.disconnect();

            // 检查是否为活跃状态
            return body.contains("\"role\":\"PRIMARY\"") && body.contains("\"status\":\"ACTIVE\"");
        } catch (Exception e) {
            log.debug("对端健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 触发紧急故障转移
     */
    private void triggerEmergencyFailover(String reason) {
        if (criticalMemoryMode) {
            return;
        }

        criticalMemoryMode = true;
        log.error("触发紧急故障转移: {}", reason);

        try {
            if (haService.isPrimary()) {
                log.info("主实例紧急降级为备实例");
                haService.emergencyDeactivate(reason);
            }
        } catch (Exception e) {
            log.error("紧急故障转移失败", e);
        }
    }

    /**
     * 获取健康状态
     */
    public HealthStatus getHealthStatus() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        double memoryUsage = (double) heapUsage.getUsed() / heapUsage.getMax();

        return HealthStatus.builder()
                .memoryUsage(memoryUsage)
                .criticalMemoryMode(criticalMemoryMode)
                .consecutiveMemoryAlerts(consecutiveMemoryAlerts)
                .consecutivePeerFailures(consecutivePeerFailures)
                .healthy(memoryUsage < memoryThreshold && consecutivePeerFailures == 0)
                .lastAlertTime(lastAlertTime)
                .build();
    }

    /**
     * 健康状态DTO
     */
    public static class HealthStatus {
        private double memoryUsage;
        private boolean criticalMemoryMode;
        private int consecutiveMemoryAlerts;
        private int consecutivePeerFailures;
        private boolean healthy;
        private long lastAlertTime;

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private HealthStatus status = new HealthStatus();

            public Builder memoryUsage(double memoryUsage) {
                status.memoryUsage = memoryUsage;
                return this;
            }

            public Builder criticalMemoryMode(boolean criticalMemoryMode) {
                status.criticalMemoryMode = criticalMemoryMode;
                return this;
            }

            public Builder consecutiveMemoryAlerts(int consecutiveMemoryAlerts) {
                status.consecutiveMemoryAlerts = consecutiveMemoryAlerts;
                return this;
            }

            public Builder consecutivePeerFailures(int consecutivePeerFailures) {
                status.consecutivePeerFailures = consecutivePeerFailures;
                return this;
            }

            public Builder healthy(boolean healthy) {
                status.healthy = healthy;
                return this;
            }

            public Builder lastAlertTime(long lastAlertTime) {
                status.lastAlertTime = lastAlertTime;
                return this;
            }

            public HealthStatus build() {
                return status;
            }
        }

        // Getters
        public double getMemoryUsage() { return memoryUsage; }
        public boolean isCriticalMemoryMode() { return criticalMemoryMode; }
        public int getConsecutiveMemoryAlerts() { return consecutiveMemoryAlerts; }
        public int getConsecutivePeerFailures() { return consecutivePeerFailures; }
        public boolean isHealthy() { return healthy; }
        public long getLastAlertTime() { return lastAlertTime; }
    }

    /**
     * 记录详细内存信息供运维分析
     */
    private void logMemoryDetails() {
        Runtime runtime = Runtime.getRuntime();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();

        log.warn("=== 内存详细信息 ===");
        log.warn("堆内存 - 已用: {} MB, 最大: {} MB, 使用率: {:.2f}%",
                heapUsage.getUsed() / 1024 / 1024,
                heapUsage.getMax() / 1024 / 1024,
                (double) heapUsage.getUsed() / heapUsage.getMax() * 100);

        log.warn("JVM - 总内存: {} MB, 空闲: {} MB, 最大: {} MB",
                runtime.totalMemory() / 1024 / 1024,
                runtime.freeMemory() / 1024 / 1024,
                runtime.maxMemory() / 1024 / 1024);

        log.warn("建议运维人员使用 jstat, jmap 等工具进行详细分析");
    }
}