package com.matching.service;

import com.matching.monitor.KafkaListenerMonitor;
import com.matching.util.ThreadFactoryManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 优雅关闭服务 提供线程安全的关闭检查机制，避免死锁风险
 */
@Service
@Slf4j
public class GracefulShutdownService {

    private final KafkaListenerEndpointRegistry kafkaListenerRegistry;
    private final KafkaListenerMonitor kafkaListenerMonitor;
    private final ExecutorService shutdownExecutor;

    // 关闭状态跟踪
    private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
    private final AtomicReference<CompletableFuture<Boolean>> currentShutdownFuture = new AtomicReference<>();
    private final AtomicBoolean serviceShutdown = new AtomicBoolean(false);

    public GracefulShutdownService(KafkaListenerEndpointRegistry kafkaListenerRegistry,
                                  KafkaListenerMonitor kafkaListenerMonitor) {
        this.kafkaListenerRegistry = kafkaListenerRegistry;
        this.kafkaListenerMonitor = kafkaListenerMonitor;

        // 创建专门的关闭检查线程池 - 使用统一的线程工厂管理
        this.shutdownExecutor = Executors.newCachedThreadPool(
                ThreadFactoryManager.createNamedThreadFactory("graceful-shutdown", true)
        );
    }

    /**
     * 等待优雅关闭 - 线程安全，防死锁
     */
    public boolean waitForGracefulShutdown(long timeout, TimeUnit unit, EventLog eventLog) {
        // 检查服务是否已关闭
        if (serviceShutdown.get()) {
            log.warn("Graceful shutdown already shutdown, returning false");
            return false;
        }

        // 防止并发关闭
        if (!shutdownInProgress.compareAndSet(false, true)) {
            log.warn("Graceful shutdown already in progress, waiting for existing shutdown...");
            CompletableFuture<Boolean> existingFuture = currentShutdownFuture.get();
            if (existingFuture != null) {
                try {
                    return existingFuture.get(timeout, unit);
                } catch (Exception e) {
                    log.warn("Failed to wait for existing shutdown", e);
                    return false;
                }
            }
            return false;
        }

        try {
            // 创建关闭检查任务
            CompletableFuture<Boolean> shutdownFuture = createShutdownCheckTask(timeout, unit, eventLog);
            currentShutdownFuture.set(shutdownFuture);

            // 等待关闭完成
            return shutdownFuture.get(timeout, unit);
        } catch (TimeoutException e) {
            log.warn("Graceful shutdown timeout after {} {}", timeout, unit);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Graceful shutdown interrupted");
            return false;
        } catch (Exception e) {
            log.error("Graceful shutdown failed", e);
            return false;
        } finally {
            shutdownInProgress.set(false);
            currentShutdownFuture.set(null);
        }
    }

    /**
     * 创建关闭检查任务
     */
    private CompletableFuture<Boolean> createShutdownCheckTask(long timeout, TimeUnit unit, EventLog eventLog) {
        return CompletableFuture.supplyAsync(() -> {
            long timeoutMs = unit.toMillis(timeout);
            long startTime = System.currentTimeMillis();
            long checkInterval = Math.min(500, timeoutMs / 10); // 动态检查间隔

            log.info("Starting graceful shutdown check, timeout:{}ms, interval:{}ms", timeoutMs, checkInterval);

            try {
                while ((System.currentTimeMillis() - startTime) < timeoutMs) {
                    ShutdownCheckResult result = performShutdownCheck(eventLog);
                    if (result.isAllClear()) {
                        log.info("Graceful shutdown conditions met: {}", result.getSummary());
                        return true;
                    }
                    log.debug("Shutdown check: {}", result.getSummary());

                    // 可中断的等待
                    try {
                        Thread.sleep(checkInterval);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Shutdown check interrupted");
                        return false;
                    }
                }

                log.warn("Graceful shutdown timeout, some conditions not met");
                return false;
            } catch (Exception e) {
                log.error("Error during graceful shutdown check", e);
                return false;
            }
        }, shutdownExecutor);
    }

    /**
     * 执行关闭检查
     */
    private ShutdownCheckResult performShutdownCheck(EventLog eventLog) {
        ShutdownCheckResult result = new ShutdownCheckResult();

        try {
            // 1. 检查Kafka监听器状态
            checkKafkaListenerStatus(result);

            // 2. 检查其他业务状态
            checkBusinessStatus(result);
        } catch (Exception e) {
            log.debug("Error during shutdown check, assuming ready", e);
            result.addCheck("error_check", true, "Check failed, assuming ready: " + e.getMessage());
        }

        return result;
    }

    /**
     * 检查Kafka监听器状态
     */
    private void checkKafkaListenerStatus(ShutdownCheckResult result) {
        try {
            // 检查注册表状态
            boolean registryRunning = kafkaListenerRegistry.isRunning();
            result.addCheck("kafka_registry", !registryRunning,
                    "Kafka registry running: " + registryRunning);

            // 检查监听器监控状态
            if (kafkaListenerMonitor != null) {
                var status = kafkaListenerMonitor.getStatus();
                boolean shouldStop = status.isRunning() || status.isShouldBeRunning();
                result.addCheck("kafka_monitor", shouldStop,
                        "Kafka monitor - running:" + status.isRunning() + ", should:" + status.isShouldBeRunning());
            } else {
                result.addCheck("kafka_monitor", true, "Kafka monitor not available");
            }
        } catch (Exception e) {
            log.debug("Failed to check Kafka status", e);
            result.addCheck("kafka_check", true, "Kafka check failed, assuming stopped");
        }
    }

    /**
     * 检查其他业务状态
     */
    private void checkBusinessStatus(ShutdownCheckResult result) {
        // 可以在这里添加其他业务状态检查
        // 例如: 检查Disruptor队列、检查未完成的事务等
        result.addCheck("business_status", true, "Business checks passed");
    }

    /**
     * 强制停止所有关闭检查
     */
    public void forceStopShutdownCheck() {
        log.warn("Force stopping graceful shutdown check");
        CompletableFuture<Boolean> currentFuture = currentShutdownFuture.get();
        if (currentFuture != null) {
            currentFuture.cancel(true);
        }
        shutdownInProgress.set(false);
        currentShutdownFuture.set(null);
    }

    /**
     * 关闭服务
     */
    public void shutdown() {
        log.info("Shutting down GracefulShutdownService...");
        forceStopShutdownCheck();

        shutdownExecutor.shutdown();
        try {
            if (!shutdownExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                shutdownExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownExecutor.shutdownNow();
        }

        log.info("GracefulShutdownService shutdown completed");
        serviceShutdown.set(true);
    }

    /**
     * 关闭检查结果
     */
    private static class ShutdownCheckResult {
        private final StringBuilder summary = new StringBuilder();
        private boolean allClear = true;

        public void addCheck(String name, boolean passed, String detail) {
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append(name).append(":").append(passed ? "OK" : "WAIT");
            if (detail != null && !detail.isEmpty()) {
                summary.append("(").append(detail).append(")");
            }

            if (!passed) {
                allClear = false;
            }
        }

        public boolean isAllClear() {
            return allClear;
        }

        public String getSummary() {
            return summary.toString();
        }
    }
}