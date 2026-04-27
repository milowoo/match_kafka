package com.matching.service.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警服务 - 负责发送各种告警信息
 */
@Component
@Slf4j
public class AlertService {

    @Value("${matching.outbox.alert.interval-ms:60000}")
    private long alertIntervalMs;

    @Value("${matching.outbox.alert.critical.enabled:true}")
    private boolean criticalAlertEnabled;

    private final AtomicLong alertCounter = new AtomicLong(0);
    private volatile long lastAlertTime = 0;

    /**
     * 发送告警（带频率限制）
     */
    public void sendAlert(String message, String uidKey) {
        long now = System.currentTimeMillis();
        if (now - lastAlertTime > alertIntervalMs) {
            long alertCount = alertCounter.incrementAndGet();
            log.error("[ALERT-{}] {} - uidKey: {}, total alerts: {}",
                    alertCount, message, uidKey, alertCount);
            lastAlertTime = now;

            // TODO: 集成外部告警系统（钉钉、邮件等）
            // alertService.sendAlert("Matching Service Data Risk", message + " - " + uidKey);
        }
    }

    /**
     * 发送严重告警（无延迟，不受频率限制）
     */
    public void sendCriticalAlert(String message, String uidKey) {
        if (!criticalAlertEnabled) {
            return;
        }

        long alertCount = alertCounter.incrementAndGet();
        log.error("[CRITICAL-ALERT-{}] {} - uidKey: {}", alertCount, message, uidKey);

        // TODO: 立即发送严重告警
        // alertService.sendCriticalAlert("URGENT: Matching Service Data Risk", message + " - " + uidKey);
    }

    public long getAlertCount() {
        return alertCounter.get();
    }

    public long getLastAlertTime() {
        return lastAlertTime;
    }
}