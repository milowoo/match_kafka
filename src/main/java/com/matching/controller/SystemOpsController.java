package com.matching.controller;

import com.matching.service.ChronicleQueueVersionService;
import com.matching.service.GracefulShutdownService;
import com.matching.util.ThreadFactoryManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.matching.controller.OpsResponseBuilder.*;

@RestController
@RequestMapping("/ops/system")
@Slf4j
public class SystemOpsController {

    private final GracefulShutdownService gracefulShutdownService;
    private final ChronicleQueueVersionService versionService;

    public SystemOpsController(GracefulShutdownService gracefulShutdownService,
                               ChronicleQueueVersionService versionService) {
        this.gracefulShutdownService = gracefulShutdownService;
        this.versionService = versionService;
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> getVersionInfo() {
        try {
            var info = versionService.getVersionInfo();
            Map<String, Object> resp = map();
            resp.put("chronicleQueue", Map.of(
                    "currentVersion", info.currentVersion,
                    "versionStatus", info.status.toString(),
                    "message", info.message,
                    "minStableVersion", info.minStableVersion,
                    "maxTestedVersion", info.maxTestedVersion,
                    "isStable", info.status == ChronicleQueueVersionService.VersionStatus.STABLE
            ));
            return ok(resp);
        } catch (Exception e) {
            log.error("Failed to get version info", e);
            return error("Failed to get version info: " + e.getMessage());
        }
    }

    @GetMapping("/threads")
    public ResponseEntity<Map<String, Object>> getThreadState() {
        try {
            var stats = ThreadFactoryManager.getThreadStats();
            Map<String, Object> resp = map();
            resp.put("threadState", Map.of(
                    "totalThreadsCreated", stats.totalThreadsCreated,
                    "activeGroups", stats.activeGroups,
                    "instanceId", stats.instanceId,
                    "groupCounters", stats.groupCounters
            ));
            return ok(resp);
        } catch (Exception e) {
            log.error("Failed to get thread stats", e);
            return error("Failed to get thread state: " + e.getMessage());
        }
    }

    @PostMapping("/force-stop-shutdown")
    public ResponseEntity<Map<String, Object>> forceStopShutdown() {
        try {
            log.warn("Received force stop shutdown request");
            gracefulShutdownService.forceStopShutdownCheck();
            return ok("Graceful shutdown check force stopped");
        } catch (Exception e) {
            log.error("Failed to force stop shutdown check", e);
            return error("Failed to force stop: " + e.getMessage());
        }
    }
}