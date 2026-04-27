package com.matching.controller;

import com.matching.service.ResultOutboxService;
import com.matching.service.outbox.RetryQueueStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/ops/outbox")
@Slf4j
public class OutboxMonitorController {

    @Autowired
    private ResultOutboxService resultOutboxService;

    /**
     * 获取重试队列状态信息
     */
    @GetMapping("/status")
    public Map<String, Object> getOutboxStatus() {
        try {
            RetryQueueStatus status = resultOutboxService.getRetryQueueStatus();

            Map<String, Object> response = new HashMap<>();
            response.put("retryQueueSize", status.getRetryQueueSize());
            response.put("emergencyQueueSize", status.getEmergencyQueueSize());
            response.put("maxRetryQueueSize", status.getMaxRetryQueueSize());
            response.put("blockOnFullQueue", status.isBlockOnFullQueue());
            response.put("maxBlockTimeMs", status.getMaxBlockTimeMs());
            response.put("healthy", status.isHealthy());
            response.put("riskLevel", status.getRiskLevel());
            response.put("utilizationPercent",
                    Math.round((double) status.getRetryQueueSize() / status.getMaxRetryQueueSize() * 100));
            response.put("timestamp", System.currentTimeMillis());
            return response;
        } catch (Exception e) {
            log.error("Failed to get outbox status", e);
            Map<String, Object> response = new HashMap<>();
            response.put("error", "Failed to get status: " + e.getMessage());
            response.put("timestamp", System.currentTimeMillis());
            return response;
        }
    }

    /**
     * 获取重试队列健康检查
     */
    @GetMapping("/health")
    public Map<String, Object> getOutboxHealth() {
        try {
            RetryQueueStatus status = resultOutboxService.getRetryQueueStatus();

            Map<String, Object> response = new HashMap<>();
            response.put("status", status.isHealthy() ? "UP" : "DOWN");
            response.put("riskLevel", status.getRiskLevel());

            Map<String, Object> details = new HashMap<>();
            details.put("retryQueueSize", status.getRetryQueueSize());
            details.put("emergencyQueueSize", status.getEmergencyQueueSize());
            details.put("maxRetryQueueSize", status.getMaxRetryQueueSize());
            details.put("utilizationPercent",
                    Math.round((double) status.getRetryQueueSize() / status.getMaxRetryQueueSize() * 100));

            response.put("details", details);
            return response;
        } catch (Exception e) {
            log.error("Failed to get outbox health", e);
            Map<String, Object> response = new HashMap<>();
            response.put("status", "DOWN");
            response.put("error", e.getMessage());
            return response;
        }
    }
}