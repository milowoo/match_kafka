package com.matching.controller;

import com.matching.dto.ApiResult;
import com.matching.service.chronicle.ChronicleQueueCleanupService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/ops/chronicle")
public class ChronicleQueueController {

    @Autowired
    private ChronicleQueueCleanupService cleanupService;

    /**
     * 手动触发所有交易对的清理 POST /ops/chronicle/cleanup/all
     */
    @PostMapping("/cleanup/all")
    public ApiResult<String> cleanupAll() {
        try {
            cleanupService.cleanupAllSymbols();
            return ApiResult.ok("Cleanup started for all symbols");
        } catch (Exception e) {
            log.error("Failed to start cleanup for all symbols", e);
            return ApiResult.error("Failed to start cleanup: " + e.getMessage());
        }
    }

    /**
     * 手动触发指定交易对的清理 POST /ops/chronicle/cleanup/{symbol}
     */
    @PostMapping("/cleanup/{symbol}")
    public ApiResult<String> cleanupSymbol(@PathVariable String symbol) {
        try {
            cleanupService.cleanupSymbol(symbol.toUpperCase());
            return ApiResult.ok("Cleanup started for symbol: " + symbol);
        } catch (Exception e) {
            log.error("Failed to start cleanup for symbol: {}", symbol, e);
            return ApiResult.error("Failed to start cleanup: " + e.getMessage());
        }
    }

    /**
     * 查看清理状态 GET /ops/chronicle/cleanup/status
     */
    @GetMapping("/cleanup/status")
    public ApiResult<CleanupStatus> getCleanupStatus() {
        try {
            CleanupStatus status = cleanupService.getCleanupStatus();
            return ApiResult.ok(status);
        } catch (Exception e) {
            log.error("Failed to get cleanup status", e);
            return ApiResult.error("Failed to get status: " + e.getMessage());
        }
    }

    /**
     * 查看指定交易对的清理状态 GET /ops/chronicle/cleanup/{symbol}/status
     */
    @GetMapping("/cleanup/{symbol}/status")
    public ApiResult<SymbolCleanupStatus> getSymbolCleanupStatus(@PathVariable String symbol) {
        try {
            SymbolCleanupStatus status = cleanupService.getSymbolCleanupStatus(symbol.toUpperCase());
            return ApiResult.ok(status);
        } catch (Exception e) {
            log.error("Failed to get cleanup status for symbol: {}", symbol, e);
            return ApiResult.error("Failed to get status: " + e.getMessage());
        }
    }

    /**
     * 清理预览（不实际删除）GET /ops/chronicle/cleanup/{symbol}/preview
     */
    @GetMapping("/cleanup/{symbol}/preview")
    public ApiResult<CleanupPreview> previewCleanup(@PathVariable String symbol) {
        try {
            CleanupPreview preview = cleanupService.previewCleanup(symbol.toUpperCase());
            return ApiResult.ok(preview);
        } catch (Exception e) {
            log.error("Failed to preview cleanup for symbol: {}", symbol, e);
            return ApiResult.error("Failed to preview: " + e.getMessage());
        }
    }

    /**
     * 紧急停止清理 POST /ops/chronicle/cleanup/stop
     */
    @PostMapping("/cleanup/stop")
    public ApiResult<String> stopCleanup() {
        try {
            cleanupService.stopCleanup();
            return ApiResult.ok("Cleanup stopped");
        } catch (Exception e) {
            log.error("Failed to stop cleanup", e);
            return ApiResult.error("Failed to stop cleanup: " + e.getMessage());
        }
    }

    /**
     * 创建检查点 POST /ops/chronicle/{symbol}/checkpoint
     */
    @PostMapping("/{symbol}/checkpoint")
    public ApiResult<String> createCheckpoint(@PathVariable String symbol) {
        try {
            cleanupService.createCheckpoint(symbol.toUpperCase());
            return ApiResult.ok("Checkpoint created for symbol: " + symbol);
        } catch (Exception e) {
            log.error("Failed to create checkpoint for symbol: {}", symbol, e);
            return ApiResult.error("Failed to create checkpoint: " + e.getMessage());
        }
    }

    /**
     * 查看存储大小 GET /ops/chronicle/{symbol}/size
     */
    @GetMapping("/{symbol}/size")
    public ApiResult<StorageInfo> getStorageSize(@PathVariable String symbol) {
        try {
            StorageInfo info = cleanupService.getStorageInfo(symbol.toUpperCase());
            return ApiResult.ok(info);
        } catch (Exception e) {
            log.error("Failed to get storage size for symbol: {}", symbol, e);
            return ApiResult.error("Failed to get storage size: " + e.getMessage());
        }
    }

    /**
     * 健康检查 GET /ops/chronicle/{symbol}/health
     */
    @GetMapping("/{symbol}/health")
    public ApiResult<HealthStatus> healthCheck(@PathVariable String symbol) {
        try {
            HealthStatus health = cleanupService.healthCheck(symbol.toUpperCase());
            return ApiResult.ok(health);
        } catch (Exception e) {
            log.error("Failed to check health for symbol: {}", symbol, e);
            return ApiResult.error("Failed to check health: " + e.getMessage());
        }
    }

    /**
     * 获取清理配置 GET /ops/chronicle/config
     */
    @GetMapping("/config")
    public ApiResult<Map<String, Object>> getCleanupConfig() {
        try {
            Map<String, Object> config = cleanupService.getCleanupConfig();
            return ApiResult.ok(config);
        } catch (Exception e) {
            log.error("Failed to get cleanup config", e);
            return ApiResult.error("Failed to get config: " + e.getMessage());
        }
    }

    /**
     * 更新清理配置 POST /ops/chronicle/config
     */
    @PostMapping("/config")
    public ApiResult<String> updateCleanupConfig(@RequestBody Map<String, Object> config) {
        try {
            cleanupService.updateCleanupConfig(config);
            return ApiResult.ok("Cleanup config updated");
        } catch (Exception e) {
            log.error("Failed to update cleanup config", e);
            return ApiResult.error("Failed to update config: " + e.getMessage());
        }
    }

    // DTO类定义
    @Data
    @AllArgsConstructor
    public static class CleanupStatus {
        private boolean running;
        private LocalDateTime lastRunTime;
        private LocalDateTime nextRunTime;
        private int totalSymbols;
        private int completedSymbols;
        private List<String> currentlyProcessing;
        private long totalFilesDeleted;
        private long totalSpaceFreed; // bytes
    }

    @Data
    @AllArgsConstructor
    public static class SymbolCleanupStatus {
        private String symbol;
        private boolean running;
        private LocalDateTime lastCleanupTime;
        private int filesDeleted;
        private long spaceFreed; // bytes
        private String status; // SUCCESS, FAILED, RUNNING
        private String errorMessage;
    }

    @Data
    @AllArgsConstructor
    public static class CleanupPreview {
        private String symbol;
        private int filesToDelete;
        private long spaceToFree; // bytes
        private List<String> fileNames;
        private LocalDateTime oldestFile;
        private LocalDateTime newestFile;
    }

    @Data
    @AllArgsConstructor
    public static class StorageInfo {
        private String symbol;
        private long totalSize; // bytes
        private int fileCount;
        private LocalDateTime oldestFile;
        private LocalDateTime newestFile;
        private long availableSpace; // bytes
        private double usagePercentage;
    }

    @Data
    @AllArgsConstructor
    public static class HealthStatus {
        private String symbol;
        private boolean healthy;
        private String status; // HEALTHY, WARNING, CRITICAL
        private List<String> issues;
        private Map<String, Object> metrics;
    }
}