package com.matching.service.outbox;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 重试队列状态信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetryQueueStatus {

    private int retryQueueSize;
    private int emergencyQueueSize;
    private long totalAlerts;
    private long lastAlertTime;
    private boolean blockOnFullQueue;
    private int maxRetryQueueSize;
    private String failureLogDir;
    private boolean dataSanitizationEnabled;
    private boolean criticalAlertEnabled;
    private long maxBlockTimeMs;

    /**
     * 检查系统是否健康
     */
    public boolean isHealthy() {
        double utilizationRate = maxRetryQueueSize > 0 ?
                (double) retryQueueSize / maxRetryQueueSize : 0;
        return utilizationRate < 0.8 && emergencyQueueSize < 100;
    }

    /**
     * 获取风险等级
     */
    public String getRiskLevel() {
        double utilizationRate = maxRetryQueueSize > 0 ?
                (double) retryQueueSize / maxRetryQueueSize : 0;

        if (emergencyQueueSize > 500 || utilizationRate > 0.9) {
            return "HIGH";
        } else if (emergencyQueueSize > 100 || utilizationRate > 0.7) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }
}