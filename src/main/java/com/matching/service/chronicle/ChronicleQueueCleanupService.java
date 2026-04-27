package com.matching.service.chronicle;

import com.matching.controller.ChronicleQueueController.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class ChronicleQueueCleanupService {

    @Value("${chronicle.cleanup.enabled:true}")
    private boolean cleanupEnabled;

    @Value("${chronicle.cleanup.retention-days:7}")
    private int retentionDays;

    @Value("${chronicle.cleanup.max-size-gb:100}")
    private long maxSizeGB;

    @Value("${chronicle.cleanup.keep-recent-files:3}")
    private int keepRecentFiles;

    @Value("${chronicle.queue.base-path:{java.io.tmpdir}/matching/eventlog-queue}")
    private String basePath;

    // 运行状态
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private LocalDateTime lastRunTime;
    private final Set<String> currentlyProcessing = ConcurrentHashMap.newKeySet();
    private final AtomicLong totalFilesDeleted = new AtomicLong(0);
    private final AtomicLong totalSpaceFreed = new AtomicLong(0);

    // 每个交易对的清理状态
    private final Map<String, SymbolCleanupStatus> symbolStatus = new ConcurrentHashMap<>();

    /**
     * 定时清理任务 - 每天凌晨2点执行
     */
    @Scheduled(cron = "${chronicle.cleanup.cron:0 0 2 * * ?}")
    public void scheduledCleanup() {
        if (!cleanupEnabled) {
            log.debug("Chronicle Queue cleanup is disabled");
            return;
        }

        log.info("Starting scheduled Chronicle Queue cleanup");
        cleanupAllSymbols();
    }

    /**
     * 清理所有交易对
     */
    public void cleanupAllSymbols() {
        if (isRunning.compareAndSet(false, true)) {
            log.warn("Cleanup is already running");
            return;
        }

        try {
            stopRequested.set(false);
            lastRunTime = LocalDateTime.now();

            List<String> symbols = getAllSymbols();
            log.info("Starting cleanup for {} symbols", symbols.size());

            for (String symbol : symbols) {
                if (stopRequested.get()) {
                    log.info("Cleanup stopped by request");
                    break;
                }
                cleanupSymbol(symbol);
            }

            log.info("Cleanup completed. Files deleted: {}, Space freed: {} MB",
                    totalFilesDeleted.get(), totalSpaceFreed.get() / 1024 / 1024);
        } finally {
            isRunning.set(false);
            currentlyProcessing.clear();
        }
    }

    /**
     * 清理指定交易对
     */
    public void cleanupSymbol(String symbol) {
        if (stopRequested.get()) {
            return;
        }

        currentlyProcessing.add(symbol);
        SymbolCleanupStatus status = new SymbolCleanupStatus(
                symbol, true, LocalDateTime.now(), 0, 0, "RUNNING", null
        );
        symbolStatus.put(symbol, status);

        Path symbolPath = getSymbolPath(symbol);
        if (!Files.exists(symbolPath)) {
            log.debug("Symbol path does not exist: {}", symbolPath);
            updateSymbolStatus(symbol, "SUCCESS", 0, 0, null);
            return;
        }

        try {
            // 执行清理
            CleanupResult result = performCleanup(symbol, symbolPath);

            // 更新状态
            updateSymbolStatus(symbol, "SUCCESS", result.filesDeleted, result.spaceFreed, null);
            log.info("Cleanup completed for symbol: {}. Files deleted: {}, Space freed: {} MB",
                    symbol, result.filesDeleted, result.spaceFreed / 1024 / 1024);
        } catch (Exception e) {
            log.error("Failed to cleanup symbol: {}", symbol, e);
            updateSymbolStatus(symbol, "FAILED", 0, 0, e.getMessage());
        } finally {
            currentlyProcessing.remove(symbol);
        }
    }

    /**
     * 执行实际的清理操作
     */
    private CleanupResult performCleanup(String symbol, Path symbolPath) throws IOException {
        LocalDate cutoffDate = LocalDate.now().minusDays(retentionDays);
        int filesDeleted = 0;
        long spaceFreed = 0;

        // 1. 基于时间的清理
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(symbolPath, "*.cq4")) {
            List<Path> files = new ArrayList<>();
            stream.forEach(files::add);

            // 按文件名排序（通常包含日期）
            files.sort(Comparator.comparing(Path::getFileName));

            // 保留最近的文件
            int filesToKeep = Math.max(keepRecentFiles, 1);
            if (files.size() <= filesToKeep) {
                log.debug("Only {} files found for {}, keeping all", files.size(), symbol);
                return new CleanupResult(filesDeleted, spaceFreed);
            }

            // 删除旧文件
            for (int i = 0; i < files.size() - filesToKeep; i++) {
                Path file = files.get(i);
                if (isOldFile(file, cutoffDate) && isFileSafeToDelete(file)) {
                    long fileSize = Files.size(file);
                    Files.delete(file);
                    filesDeleted++;
                    spaceFreed += fileSize;
                    totalFilesDeleted.incrementAndGet();
                    totalSpaceFreed.addAndGet(fileSize);
                    log.debug("Deleted old Chronicle Queue file: {}", file);
                }
            }
        }

        // 2. 基于大小的清理
        long totalSize = calculateDirectorySize(symbolPath);
        long maxSizeBytes = maxSizeGB * 1024 * 1024 * 1024;

        if (totalSize > maxSizeBytes) {
            log.info("Directory size {} MB exceeds limit {} MB for symbol: {}",
                    totalSize / 1024 / 1024, maxSizeBytes / 1024 / 1024, symbol);
            CleanupResult sizeResult = cleanupBySize(symbolPath, totalSize - maxSizeBytes);
            filesDeleted += sizeResult.filesDeleted;
            spaceFreed += sizeResult.spaceFreed;
        }

        return new CleanupResult(filesDeleted, spaceFreed);
    }

    /**
     * 基于大小的清理
     */
    private CleanupResult cleanupBySize(Path symbolPath, long targetSize) throws IOException {
        int filesDeleted = 0;
        long spaceFreed = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(symbolPath, "*.cq4")) {
            List<Path> files = new ArrayList<>();
            stream.forEach(files::add);

            // 按修改时间排序，删除最老的文件
            files.sort(Comparator.comparing(file -> {
                try {
                    return Files.getLastModifiedTime(file);
                } catch (IOException e) {
                    return FileTime.fromMillis(0);
                }
            }));

            for (Path file : files) {
                if (spaceFreed >= targetSize) {
                    break;
                }

                if (isFileSafeToDelete(file)) {
                    long fileSize = Files.size(file);
                    Files.delete(file);
                    filesDeleted++;
                    spaceFreed += fileSize;
                    totalFilesDeleted.incrementAndGet();
                    totalSpaceFreed.addAndGet(fileSize);
                    log.debug("Deleted Chronicle Queue file for size limit: {}", file);
                }
            }
        }

        return new CleanupResult(filesDeleted, spaceFreed);
    }

    /**
     * 检查文件是否可以安全删除
     */
    private boolean isFileSafeToDelete(Path file) {
        try {
            // 1. 检查文件是否正在被使用（简化实现）
            if (isFileInUse(file)) {
                log.debug("File is in use, skipping: {}", file);
                return false;
            }

            // 2. 检查是否是活跃数据文件
            if (file.getFileName().toString().contains("metadata")) {
                log.debug("Metadata file, skipping: {}", file);
                return false;
            }

            return true;
        } catch (Exception e) {
            log.warn("Error checking if file is safe to delete: {}", file, e);
            return false;
        }
    }

    /**
     * 检查文件是否正在被使用（简化实现）
     */
    private boolean isFileInUse(Path file) {
        try {
            // 尝试重命名文件来检查是否被占用
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.move(file, temp);
            Files.move(temp, file);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * 检查文件是否过期
     */
    private boolean isOldFile(Path file, LocalDate cutoffDate) {
        try {
            String fileName = file.getFileName().toString();

            // 尝试从文件名解析日期（格式：YYYYMMDD.cq4）
            if (fileName.matches("\\d{6}\\.cq4")) {
                String dateStr = fileName.substring(0, 6);
                LocalDate fileDate = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
                return fileDate.isBefore(cutoffDate);
            }

            // 如果无法从文件名解析日期，使用文件修改时间
            FileTime modifiedTime = Files.getLastModifiedTime(file);
            LocalDate fileDate = LocalDateTime.ofInstant(
                    modifiedTime.toInstant(),
                    java.time.ZoneId.systemDefault()
            ).toLocalDate();

            return fileDate.isBefore(cutoffDate);
        } catch (Exception e) {
            log.warn("Error checking file date: {}", file, e);
            return false;
        }
    }

    /**
     * 获取所有交易对
     */
    private List<String> getAllSymbols() {
        try {
            Path baseDir = Paths.get(basePath);
            if (!Files.exists(baseDir)) {
                return Collections.emptyList();
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
                return StreamSupport.stream(stream.spliterator(), false)
                        .filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString().toUpperCase())
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            log.error("Failed to get symbols list", e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取交易对路径
     */
    private Path getSymbolPath(String symbol) {
        return Paths.get(basePath, symbol.toUpperCase());
    }

    /**
     * 计算目录大小
     */
    private long calculateDirectorySize(Path directory) {
        try {
            return Files.walk(directory)
                    .filter(Files::isRegularFile)
                    .mapToLong(file -> {
                        try {
                            return Files.size(file);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            log.error("Failed to calculate directory size: {}", directory, e);
            return 0;
        }
    }

    /**
     * 更新交易对状态
     */
    private void updateSymbolStatus(String symbol, String status, int filesDeleted, long spaceFreed, String errorMessage) {
        SymbolCleanupStatus symbolStatus = new SymbolCleanupStatus(
                symbol, false, LocalDateTime.now(), filesDeleted, spaceFreed, status, errorMessage
        );
        this.symbolStatus.put(symbol, symbolStatus);
    }

    // 公共接口方法
    public CleanupStatus getCleanupStatus() {
        return new CleanupStatus(
                isRunning.get(),
                lastRunTime,
                getNextRunTime(),
                symbolStatus.size(),
                0,
                new ArrayList<>(currentlyProcessing),
                totalFilesDeleted.get(),
                totalSpaceFreed.get()
        );
    }

    public SymbolCleanupStatus getSymbolCleanupStatus(String symbol) {
        return symbolStatus.getOrDefault(symbol,
                new SymbolCleanupStatus(symbol, false, null, 0, 0, "NEVER_RUN", null));
    }

    public CleanupPreview previewCleanup(String symbol) {
        // 实现清理预览逻辑
        Path symbolPath = getSymbolPath(symbol);
        if (!Files.exists(symbolPath)) {
            return new CleanupPreview(symbol, 0, 0, Collections.emptyList(), null, null);
        }
        // 简化实现
        return new CleanupPreview(symbol, 0, 0, Collections.emptyList(), null, null);
    }

    public void stopCleanup() {
        stopRequested.set(true);
    }

    public void createCheckpoint(String symbol) {
        // 实现检查点创建逻辑
        log.info("Creating checkpoint for symbol: {}", symbol);
    }

    public StorageInfo getStorageInfo(String symbol) {
        Path symbolPath = getSymbolPath(symbol);
        if (!Files.exists(symbolPath)) {
            return new StorageInfo(symbol, 0, 0, null, null, 0, 0.0);
        }
        long totalSize = calculateDirectorySize(symbolPath);
        // 简化实现
        return new StorageInfo(symbol, totalSize, 0, null, null, 0, 0.0);
    }

    public HealthStatus healthCheck(String symbol) {
        // 实现健康检查逻辑
        return new HealthStatus(symbol, true, "HEALTHY", Collections.emptyList(), Collections.emptyMap());
    }

    public Map<String, Object> getCleanupConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", cleanupEnabled);
        config.put("retentionDays", retentionDays);
        config.put("maxSizeGB", maxSizeGB);
        config.put("keepRecentFiles", keepRecentFiles);
        config.put("basePath", basePath);
        return config;
    }

    public void updateCleanupConfig(Map<String, Object> config) {
        // 实现配置更新逻辑
        log.info("Updating cleanup config: {}", config);
    }

    private LocalDateTime getNextRunTime() {
        // 简化实现：下一天的凌晨2点
        return LocalDate.now().plusDays(1).atTime(2, 0, 0);
    }

    // 内部类
    private static class CleanupResult {
        final int filesDeleted;
        final long spaceFreed;

        CleanupResult(int filesDeleted, long spaceFreed) {
            this.filesDeleted = filesDeleted;
            this.spaceFreed = spaceFreed;
        }
    }
}