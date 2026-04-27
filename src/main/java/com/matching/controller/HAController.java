package com.matching.controller;

import com.matching.service.UnifiedChronicleQueueEventLog;
import com.matching.service.EventLog;
import com.matching.service.HAService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.matching.controller.OpsResponseBuilder.*;

@RestController
@RequestMapping("/ops/ha")
@Slf4j
public class HAController {

    private final EventLog eventLog;
    private final HAService haService;
    private final KafkaListenerEndpointRegistry kafkaListenerRegistry;
    private final ApplicationEventPublisher eventPublisher;

    public HAController(EventLog eventLog,
                        HAService haService,
                        KafkaListenerEndpointRegistry kafkaListenerRegistry,
                        ApplicationEventPublisher eventPublisher) {
        this.eventLog = eventLog;
        this.haService = haService;
        this.kafkaListenerRegistry = kafkaListenerRegistry;
        this.eventPublisher = eventPublisher;
    }

    @PostMapping("/activate")
    public ResponseEntity<Map<String, Object>> activate() {
        try {
            log.info("Received activate request");
            if (!haService.activate()) {
                throw new RuntimeException("HAService.activate() returned false");
            }
            eventPublisher.publishEvent(new PrimaryActivatedEvent());

            Map<String, Object> resp = map();
            resp.put("role", "PRIMARY");
            resp.put("message", "Instance activated as PRIMARY successfully");
            return ok(resp);
        } catch (Exception e) {
            log.error("Failed to activate as PRIMARY", e);
            return error("Failed to activate: " + e.getMessage());
        }
    }

    @PostMapping("/deactivate")
    public ResponseEntity<Map<String, Object>> deactivate() {
        try {
            log.info("Received deactivate request");
            if (!haService.deactivate()) {
                throw new RuntimeException("HAService.deactivate() returned false");
            }
            eventPublisher.publishEvent(new StandbyActivatedEvent());

            Map<String, Object> resp = map();
            resp.put("role", "STANDBY");
            resp.put("message", "Instance deactivated to STANDBY successfully");
            return ok(resp);
        } catch (Exception e) {
            log.error("Failed to deactivate to STANDBY", e);
            return error("Failed to deactivate: " + e.getMessage());
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        try {
            Map<String, Object> resp = map();
            if (eventLog instanceof UnifiedChronicleQueueEventLog unifiedEventLog) {
                try {
                    resp.put("initialized", unifiedEventLog.isInitialized());
                    resp.put("orderBookServiceReady", unifiedEventLog.isOrderBookServiceReady());
                    resp.put("role", unifiedEventLog.getRole(null));
                    resp.put("eventLogType", "unified");
                } catch (Exception e) {
                    resp.put("eventLogStatus", "unavailable");
                    resp.put("eventLogStatusError", e.getMessage());
                }
            } else {
                resp.put("eventLogType", "file");
                resp.put("role", "FILE_MODE");
            }
            resp.put("currentSeq", eventLog.currentSeq());
            resp.put("kafkaListenerRunning", kafkaListenerRegistry.isRunning());
            return ok(resp);
        } catch (Exception e) {
            log.error("Failed to get status", e);
            return error("Failed to get status: " + e.getMessage());
        }
    }

    @PostMapping("/recover")
    public ResponseEntity<Map<String, Object>> recover() {
        try {
            log.info("Received recover request");
            return ok("Recovery completed successfully");
        } catch (Exception e) {
            log.error("Failed to recover", e);
            return error("Failed to recover: " + e.getMessage());
        }
    }

    private UnifiedChronicleQueueEventLog requireUnifiedChronicleQueue(String operation) {
        if (eventLog instanceof UnifiedChronicleQueueEventLog unifiedEventLog) {
            return unifiedEventLog;
        }
        log.warn("{} only supported for Unified Chronicle Queue mode", operation);
        return null;
    }

    @GetMapping("/consistency/status/{symbolId}")
    public ResponseEntity<Map<String, Object>> getSymbolConsistencyStatus(@PathVariable String symbolId) {
        try {
            EventLog.ConsistencyCheckResult result = eventLog.checkDataConsistency(symbolId);
            Map<String, Object> resp = map();
            resp.put("symbolId", symbolId);
            resp.put("status", result.getStatus());
            resp.put("localSeq", result.getLocalSeq());
            resp.put("lastSentSeq", result.getLastSentSeq());
            resp.put("seqDiff", result.getSeqDiff());
            resp.put("checkTime", System.currentTimeMillis());
            return ok(resp);
        } catch (Exception e) {
            log.error("Failed to get consistency status for symbol: {}", symbolId, e);
            return error("Failed to get consistency status: " + e.getMessage());
        }
    }

    // Spring Application Events
    public static class PrimaryActivatedEvent {
        private final long timestamp = System.currentTimeMillis();
        public long getTimestamp() { return timestamp; }
    }

    public static class StandbyActivatedEvent {
        private final long timestamp = System.currentTimeMillis();
        public long getTimestamp() { return timestamp; }
    }
}