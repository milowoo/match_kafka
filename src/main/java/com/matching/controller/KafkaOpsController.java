package com.matching.controller;

import com.matching.service.KafkaConsumerStartupService;
import com.matching.service.KafkaTimeoutMonitorService;
import com.matching.monitor.KafkaListenerMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.matching.controller.OpsResponseBuilder.*;

@RestController
@RequestMapping("/ops/kafka")
@Slf4j
public class KafkaOpsController {

    private final KafkaConsumerStartupService kafkaConsumerStartupService;
    private final KafkaListenerEndpointRegistry kafkaListenerRegistry;
    private final KafkaListenerMonitor kafkaListenerMonitor;
    private final KafkaTimeoutMonitorService kafkaTimeoutMonitorService;

    public KafkaOpsController(KafkaConsumerStartupService kafkaConsumerStartupService,
                             KafkaListenerEndpointRegistry kafkaListenerRegistry,
                             KafkaListenerMonitor kafkaListenerMonitor,
                             KafkaTimeoutMonitorService kafkaTimeoutMonitorService) {
        this.kafkaConsumerStartupService = kafkaConsumerStartupService;
        this.kafkaListenerRegistry = kafkaListenerRegistry;
        this.kafkaListenerMonitor = kafkaListenerMonitor;
        this.kafkaTimeoutMonitorService = kafkaTimeoutMonitorService;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startConsumers() {
        try {
            log.info("Received manual Kafka consumer start request");
            boolean started = kafkaConsumerStartupService.startConsumers();
            Map<String, Object> resp = map();
            resp.put("result", started ? "started" : "failed");
            resp.put("startupStatus", kafkaConsumerStartupService.getStartupStatus().toString());
            return ok(resp);
        } catch (Exception e) {
            log.error("Failed to start Kafka consumers", e);
            return error("Failed to start Kafka consumers: " + e.getMessage());
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopConsumers() {
        try {
            log.info("Received manual Kafka consumer stop request");
            boolean stopped = kafkaConsumerStartupService.stopConsumers();
            Map<String, Object> resp = map();
            resp.put("result", stopped ? "stopped" : "failed");
            resp.put("startupStatus", kafkaConsumerStartupService.getStartupStatus().toString());
            return ok(resp);
        } catch (Exception e) {
            log.error("Failed to stop Kafka consumers", e);
            return error("Failed to stop Kafka consumers: " + e.getMessage());
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        try {
            Map<String, Object> resp = map();
            resp.put("startupStatus", kafkaConsumerStartupService.getStartupStatus().toString());
            resp.put("kafkaListenerRunning", kafkaListenerRegistry.isRunning());
            resp.put("kafkaListenerStatus", kafkaListenerMonitor.getStatus().toString());
            resp.put("timeoutStats", kafkaTimeoutMonitorService.getTimeoutStats().toString());
            return ok(resp);
        } catch (Exception e) {
            log.error("Failed to get Kafka status", e);
            return error("Failed to get Kafka status: " + e.getMessage());
        }
    }

    @PostMapping("/reset-timeout-stats")
    public ResponseEntity<Map<String, Object>> resetTimeoutStats() {
        try {
            kafkaTimeoutMonitorService.resetStats();
            return ok("Kafka timeout statistics reset successfully");
        } catch (Exception e) {
            log.error("Failed to reset Kafka timeout statistics", e);
            return error("Failed to reset Kafka timeout statistics: " + e.getMessage());
        }
    }
}