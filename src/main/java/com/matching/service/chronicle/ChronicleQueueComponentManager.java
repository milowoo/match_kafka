package com.matching.service.chronicle;

import com.matching.service.OrderBookService;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chronicle Queue组件管理器 负责管理每个交易对的队列组件的创建、缓存和清理
 */
@Component
public class ChronicleQueueComponentManager {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChronicleQueueComponentManager.class);
    private final ChronicleQueueFactory queueFactory;
    private final ConcurrentMap<String, SymbolQueueComponents> symbolComponents = new ConcurrentHashMap<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public ChronicleQueueComponentManager(ChronicleQueueFactory queueFactory) {
        this.queueFactory = queueFactory;
    }

    /**
     * 初始化组件管理器
     */
    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            log.info("ChronicleQueueComponentManager initialized");
        }
    }

    /**
     * 获取或创建指定交易对的队列组件
     */
    public SymbolQueueComponents getOrCreateComponents(String symbolId,
                                                      boolean transactionEnabled,
                                                      AtomicLong globalSeq,
                                                      AtomicLong committedSeq,
                                                      OrderBookService orderBookService) {
        ensureInitialized();

        SymbolQueueComponents components = symbolComponents.get(symbolId);
        if (components != null) {
            return components;
        }

        // 双重检查锁定
        synchronized (this) {
            components = symbolComponents.get(symbolId);
            if (components != null) {
                return components;
            }

            try {
                log.info("Creating Chronicle Queue components for symbol: {}", symbolId);

                // 从工厂获取队列管理器
                ChronicleQueueManager queueManager = queueFactory.getQueueManager(symbolId);

                // 创建组件
                ChronicleQueueWriter writer = new ChronicleQueueWriter(queueManager, transactionEnabled, committedSeq);
                ChronicleQueueReader reader = new ChronicleQueueReader(queueManager, transactionEnabled);

                // 创建HAManager
                ChronicleQueueHAManager haManager = new ChronicleQueueHAManager(
                        queueManager, reader, queueManager.getInstanceId(), transactionEnabled, globalSeq, committedSeq);

                // 设置OrderBookService
                if (orderBookService != null) {
                    haManager.setOrderBookService(orderBookService);
                }

                ChronicleQueueMetricsCollector metricsCollector = new ChronicleQueueMetricsCollector(
                        queueManager, null, reader);

                // 恢复全局序列号
                haManager.recoverGlobalSeq();

                components = new SymbolQueueComponents(queueManager, writer, reader, haManager, metricsCollector);
                symbolComponents.put(symbolId, components);

                log.info("Chronicle Queue components created successfully for symbol: {}", symbolId);
                return components;
            } catch (Exception e) {
                log.error("Failed to create Chronicle Queue components for symbol: {}", symbolId, e);
                throw new RuntimeException("Failed to create Chronicle Queue components for symbol: " + symbolId, e);
            }
        }
    }

    /**
     * 获取已存在的组件，如果不存在返回null
     */
    public SymbolQueueComponents getComponents(String symbolId) {
        return symbolComponents.get(symbolId);
    }

    /**
     * 清理指定交易对的组件
     */
    public void cleanupComponents(String symbolId) {
        SymbolQueueComponents components = symbolComponents.remove(symbolId);
        if (components != null) {
            log.info("Cleaning up Chronicle Queue components for symbol: {}", symbolId);
            // 组件会在工厂关闭时统一清理，这里不需要额外操作
        }
    }

    /**
     * 清理所有组件
     */
    public void cleanupAllComponents() {
        log.info("Cleaning up all Chronicle Queue components...");
        for (String symbolId : symbolComponents.keySet()) {
            try {
                cleanupComponents(symbolId);
            } catch (Exception e) {
                log.warn("Error cleaning up components for symbol: {}", symbolId, e);
            }
        }
        symbolComponents.clear();
        log.info("All Chronicle Queue components cleaned up");
    }

    /**
     * 获取所有已创建的组件
     */
    public ConcurrentMap<String, SymbolQueueComponents> getAllComponents() {
        return symbolComponents;
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * 确保已初始化
     */
    private void ensureInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException("ChronicleQueueComponentManager not initialized");
        }
    }

    /**
     * 关闭组件管理器
     */
    public void shutdown() {
        log.info("Shutting down ChronicleQueueComponentManager...");
        initialized.set(false);
        cleanupAllComponents();
        log.info("ChronicleQueueComponentManager shutdown completed");
    }

    /**
     * 交易对队列组件封装类
     */
    public static class SymbolQueueComponents {
        public final ChronicleQueueManager queueManager;
        public final ChronicleQueueWriter writer;
        public final ChronicleQueueReader reader;
        public final ChronicleQueueHAManager haManager;
        public final ChronicleQueueMetricsCollector metricsCollector;

        public SymbolQueueComponents(ChronicleQueueManager queueManager,
                                     ChronicleQueueWriter writer,
                                     ChronicleQueueReader reader,
                                     ChronicleQueueHAManager haManager,
                                     ChronicleQueueMetricsCollector metricsCollector) {
            this.queueManager = queueManager;
            this.writer = writer;
            this.reader = reader;
            this.haManager = haManager;
            this.metricsCollector = metricsCollector;
        }
    }
}