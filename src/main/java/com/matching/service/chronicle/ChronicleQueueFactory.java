package com.matching.service.chronicle;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Chronicle Queue工厂类 为每个交易对创建独立的Chronicle Queue实例，避免多线程冲突
 */
@Component
public class ChronicleQueueFactory {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChronicleQueueFactory.class);

    @Value("${matching.eventlog.dir:/mnt/shared/matching/eventlog-queue}")
    private String baseQueuePath;

    @Value("${matching.ha.instance-id:node-1}")
    private String instanceId;

    @Value("${matching.ha.role:STANDBY}")
    private String initialRole;

    // 每个交易对的Chronicle Queue管理器
    private final ConcurrentMap<String, ChronicleQueueManager> queueManagers = new ConcurrentHashMap<>();

    // 保护工厂操作的读写锁
    private final ReentrantReadWriteLock factoryLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = factoryLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = factoryLock.writeLock();

    /**
     * 获取指定交易对的Chronicle Queue管理器 如果不存在则创建新的实例
     */
    public ChronicleQueueManager getQueueManager(String symbolId) {
        if (symbolId == null || symbolId.trim().isEmpty()) {
            throw new IllegalArgumentException("SymbolId cannot be null or empty");
        }

        // 先尝试读锁获取现有实例
        readLock.lock();
        try {
            ChronicleQueueManager existing = queueManagers.get(symbolId);
            if (existing != null) {
                return existing;
            }
        } finally {
            readLock.unlock();
        }

        // 需要创建新实例，获取写锁
        writeLock.lock();
        try {
            // 双重检查，防止并发创建
            ChronicleQueueManager existing = queueManagers.get(symbolId);
            if (existing != null) {
                return existing;
            }

            // 创建新的队列管理器 - 确保每个交易对有完全独立的路径
            String symbolQueuePath = baseQueuePath + "/symbol-" + symbolId.toLowerCase();
            String symbolInstanceId = instanceId + "-" + symbolId.toLowerCase() + "-" + Thread.currentThread().getId();

            log.info("Creating new Chronicle Queue manager for symbol: {}, path: {}, instanceId: {}",
                    symbolId, symbolQueuePath, symbolInstanceId);

            // 确保目录存在
            java.nio.file.Path queueDir = java.nio.file.Paths.get(symbolQueuePath);
            try {
                java.nio.file.Files.createDirectories(queueDir);
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to create queue directory: " + symbolQueuePath, e);
            }

            ChronicleQueueManager manager = new ChronicleQueueManager(
                    symbolQueuePath, symbolInstanceId, initialRole);

            // 初始化管理器
            manager.initialize();

            // 存储到映射中
            queueManagers.put(symbolId, manager);

            log.info("Chronicle Queue manager created successfully for symbol: {}, path: {}", symbolId, symbolQueuePath);
            return manager;
        } catch (Exception e) {
            log.error("Failed to create Chronicle Queue manager for symbol: {}", symbolId, e);
            throw new RuntimeException("Failed to create Chronicle Queue manager for symbol: " + symbolId, e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 检查指定交易对是否已有队列管理器
     */
    public boolean hasQueueManager(String symbolId) {
        readLock.lock();
        try {
            return queueManagers.containsKey(symbolId);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 获取所有已创建的交易对列表
     */
    public java.util.Set<String> getAllSymbols() {
        readLock.lock();
        try {
            return new java.util.HashSet<>(queueManagers.keySet());
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 移除指定交易对的队列管理器
     */
    public boolean removeQueueManager(String symbolId) {
        writeLock.lock();
        try {
            ChronicleQueueManager manager = queueManagers.remove(symbolId);
            if (manager != null) {
                try {
                    manager.shutdown();
                    log.info("Chronicle Queue manager removed for symbol: {}", symbolId);
                    return true;
                } catch (Exception e) {
                    log.warn("Error shutting down Chronicle Queue manager for symbol: {}", symbolId, e);
                    return false;
                }
            }
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 切换指定交易对的队列管理器到主模式
     */
    public boolean switchToPrimary(String symbolId) {
        readLock.lock();
        try {
            ChronicleQueueManager manager = queueManagers.get(symbolId);
            if (manager != null) {
                boolean success = manager.switchToPrimary();
                log.info("Switch to PRIMARY for symbol: {}: {}", symbolId, success ? "SUCCESS" : "FAILED");
                return success;
            }
            log.warn("No Chronicle Queue manager found for symbol: {}", symbolId);
            return false;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 切换指定交易对的队列管理器到从模式
     */
    public boolean switchToStandby(String symbolId) {
        readLock.lock();
        try {
            ChronicleQueueManager manager = queueManagers.get(symbolId);
            if (manager != null) {
                boolean success = manager.switchToStandby();
                log.info("Switch to STANDBY for symbol: {}: {}", symbolId, success ? "SUCCESS" : "FAILED");
                return success;
            }
            log.warn("No Chronicle Queue manager found for symbol: {}", symbolId);
            return false;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 切换所有交易对到主模式
     */
    public void switchAllToPrimary() {
        readLock.lock();
        try {
            log.info("Switching all Chronicle Queue managers to PRIMARY mode");
            for (String symbolId : queueManagers.keySet()) {
                switchToPrimary(symbolId);
            }
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 切换所有交易对到从模式
     */
    public void switchAllToStandby() {
        readLock.lock();
        try {
            log.info("Switching all Chronicle Queue managers to STANDBY mode");
            for (String symbolId : queueManagers.keySet()) {
                switchToStandby(symbolId);
            }
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 获取工厂状态信息
     */
    public FactoryStatus getStatus() {
        readLock.lock();
        try {
            FactoryStatus status = new FactoryStatus();
            status.totalSymbols = queueManagers.size();
            status.baseQueuePath = baseQueuePath;
            status.instanceId = instanceId;
            status.initialRole = initialRole;

            for (java.util.Map.Entry<String, ChronicleQueueManager> entry : queueManagers.entrySet()) {
                String symbolId = entry.getKey();
                ChronicleQueueManager manager = entry.getValue();
                status.symbolStatus.put(symbolId, new SymbolStatus(
                        symbolId,
                        manager.getRole(),
                        manager.getRoleState().toString(),
                        manager.isSwitching()
                ));
            }

            return status;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 关闭所有队列管理器
     */
    @PreDestroy
    public void shutdown() {
        writeLock.lock();
        try {
            log.info("Shutting down Chronicle Queue Factory with {} managers", queueManagers.size());
            for (java.util.Map.Entry<String, ChronicleQueueManager> entry : queueManagers.entrySet()) {
                String symbolId = entry.getKey();
                ChronicleQueueManager manager = entry.getValue();
                try {
                    manager.shutdown();
                    log.info("Chronicle Queue manager shutdown for symbol: {}", symbolId);
                } catch (Exception e) {
                    log.warn("Error shutting down Chronicle Queue manager for symbol: {}", symbolId, e);
                }
            }
            queueManagers.clear();
            log.info("Chronicle Queue Factory shutdown completed");
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 工厂状态信息
     */
    public static class FactoryStatus {
        public int totalSymbols;
        public String baseQueuePath;
        public String instanceId;
        public String initialRole;
        public java.util.Map<String, SymbolStatus> symbolStatus = new java.util.HashMap<>();
    }

    /**
     * 交易对状态信息
     */
    public static class SymbolStatus {
        public final String symbolId;
        public final String role;
        public final String roleState;
        public final boolean switching;

        public SymbolStatus(String symbolId, String role, String roleState, boolean switching) {
            this.symbolId = symbolId;
            this.role = role;
            this.roleState = roleState;
            this.switching = switching;
        }
    }
}