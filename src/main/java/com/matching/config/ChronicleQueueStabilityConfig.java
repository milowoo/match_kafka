package com.matching.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

/**
 * Chronicle Queue稳定性配置 提供生产环境稳定性保障配置
 */
@Component
@ConfigurationProperties(prefix = "matching.chronicle-queue.stability")
@Data
@Slf4j
public class ChronicleQueueStabilityConfig {

    // 启用稳定性检查
    private boolean enableStabilityChecks = true;

    // 启用版本验证
    private boolean enableVersionValidation = true;

    // 启用保守模式（更安全的配置）
    private boolean conservativeMode = true;

    // 队列文件预分配大小 (MB) 保守模式下使用较小值避免内存问题
    private int preallocationSizeMB = conservativeMode ? 64 : 128;

    // 最大队列文件大小 (MB) 限制单个文件大小避免过大文件
    private int maxFileSizeMB = conservativeMode ? 512 : 1024;

    // 启用文件同步 保守模式下强制启用确保数据安全
    private boolean enableFileSync = conservativeMode || true;

    // 同步间隔 (毫秒)
    private int syncIntervalMs = conservativeMode ? 1000 : 5000;

    // 启用压缩
    private boolean enableCompression = false; // 稳定性优先，暂不启用压缩

    // 启用内存映射文件
    private boolean enableMemoryMapping = true;

    // 内存映射文件大小 (MB)
    private int memoryMappingSizeMB = conservativeMode ? 256 : 512;

    // 启用读取缓存
    private boolean enableReadCache = true;

    // 读取缓存大小 (MB)
    private int readCacheSizeMB = conservativeMode ? 32 : 64;

    // 启用写入缓存
    private boolean enableWriteCache = true;

    // 写入缓存大小 (MB)
    private int writeCacheSizeMB = conservativeMode ? 16 : 32;

    // 启用健康检查
    private boolean enableHealthCheck = true;

    // 健康检查间隔 (秒)
    private int healthCheckIntervalSeconds = 30;

    // 启用性能监控
    private boolean enablePerformanceMonitoring = true;

    // 性能监控间隔 (秒)
    private int performanceMonitoringIntervalSeconds = 60;

    // 启用错误恢复
    private boolean enableErrorRecovery = true;

    // 最大重试次数
    private int maxRetryAttempts = conservativeMode ? 3 : 5;

    // 重试间隔 (毫秒)
    private int retryIntervalMs = conservativeMode ? 2000 : 1000;

    // 启用备份
    private boolean enableBackup = true;

    // 备份间隔 (分钟)
    private int backupIntervalMinutes = 60;

    // 备份保留天数
    private int backupRetentionDays = 7;

    @PostConstruct
    public void logConfiguration() {
        log.info("=== Chronicle Queue 稳定性配置 ===");
        log.info("保守模式: {}", conservativeMode);
        log.info("版本验证: {}", enableVersionValidation);
        log.info("稳定性检查: {}", enableStabilityChecks);
        log.info("文件同步: {} (间隔: {}ms)", enableFileSync, syncIntervalMs);
        log.info("预分配大小: {}MB", preallocationSizeMB);
        log.info("最大文件大小: {}MB", maxFileSizeMB);
        log.info("内存映射: {} (大小: {}MB)", enableMemoryMapping, memoryMappingSizeMB);
        log.info("读取缓存: {} (大小: {}MB)", enableReadCache, readCacheSizeMB);
        log.info("写入缓存: {} (大小: {}MB)", enableWriteCache, writeCacheSizeMB);
        log.info("健康检查: {} (间隔: {}s)", enableHealthCheck, healthCheckIntervalSeconds);
        log.info("性能监控: {} (间隔: {}s)", enablePerformanceMonitoring, performanceMonitoringIntervalSeconds);
        log.info("错误恢复: {} (重试: {}次, 间隔: {}ms)", enableErrorRecovery, maxRetryAttempts, retryIntervalMs);
        log.info("备份: {} (间隔: {}分钟, 保留: {}天)", enableBackup, backupIntervalMinutes, backupRetentionDays);
        log.info("====================================");

        if (conservativeMode) {
            log.info("✓ 当前使用保守模式配置, 优先保证稳定性");
        }
    }

    /**
     * 获取稳定性配置摘要
     */
    public String getConfigurationSummary() {
        return String.format(
                "ChronicleQueue稳定性配置: 保守模式=%s, 版本验证=%s, 文件同步=%s, 预分配=%dMB, 最大文件=%dMB",
                conservativeMode, enableVersionValidation, enableFileSync,
                preallocationSizeMB, maxFileSizeMB
        );
    }
}