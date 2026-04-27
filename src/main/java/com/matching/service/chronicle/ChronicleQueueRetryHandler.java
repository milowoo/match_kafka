package com.matching.service.chronicle;

import com.matching.service.EventLog;
import com.matching.service.ChronicleQueueWriteException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Chronicle Queue重试处理器，负责处理写入重试逻辑和异常恢复
 */
@Component
public class ChronicleQueueRetryHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChronicleQueueRetryHandler.class);

    @Value("${matching.eventlog.efs-optimized:false}")
    private boolean networkOptimized;

    @Value("${matching.eventlog.network-timeout:30000}")
    private long networkTimeout;

    @Value("${matching.eventlog.retry-attempts:3}")
    private int retryAttempts;

    @Value("${matching.eventlog.retry-delay:1000}")
    private long retryDelay;

    @Value("${matching.eventlog.chronicle.forceSync:false}")
    private boolean forceSyncEnabled;

    /**
     * 带有重试机制的事件写入
     */
    public void writeEventWithRetry(ChronicleQueueComponentManager.SymbolQueueComponents components,
                                     EventLog.Event event, String symbolId, long seq) throws Exception {
        int maxRetries = networkOptimized ? retryAttempts : 3;
        long retryDelayMs = networkOptimized ? retryDelay : 10;

        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (forceSyncEnabled && networkOptimized) {
                    components.writer.writeEventSync(event);
                } else {
                    components.writer.writeEvent(event);
                }

                // 写入成功，返回
                if (attempt > 1) {
                    log.info("[ChronicleQueue] Write succeeded on attempt {} for symbol {}, seq: {}",
                            attempt, symbolId, seq);
                }
                return;
            } catch (Exception e) {
                lastException = e;

                if (attempt < maxRetries) {
                    // 检查是否为可恢复的异常
                    if (isRecoverableIOException(e)) {
                        log.warn("[ChronicleQueue] Write attempt {} failed for symbol {}, seq: {}, retrying in {}ms",
                                attempt, symbolId, seq, retryDelayMs, e);
                        try {
                            Thread.sleep(retryDelayMs);
                            if (networkOptimized) {
                                retryDelayMs = Math.min(retryDelayMs * 2, networkTimeout / 4);
                            } else {
                                retryDelayMs *= 2;
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new ChronicleQueueWriteException(symbolId, seq, "Write interrupted", ie);
                        }
                    } else {
                        // 不可恢复的异常，直接抛出
                        throw e;
                    }
                } else {
                    log.error("[ChronicleQueue] All {} write attempts failed for symbol {}, seq: {}",
                            maxRetries, symbolId, seq, e);
                }
            }
        }

        // 所有重试均失败
        throw lastException;
    }

    /**
     * 判断是否为可恢复的IO异常
     */
    public boolean isRecoverableIOException(Exception e) {
        if (networkOptimized) {
            return false;
        }

        String message = e.getMessage();
        if (message == null) {
            return false;
        }

        // 常见的可恢复IO异常
        return message.contains("Stale file handle") ||
               message.contains("Connection timed out") ||
               message.contains("Network is unreachable") ||
               message.contains("Resource temporarily unavailable") ||
               message.contains("Input/output error") ||
               message.contains("Connection reset") ||
               message.contains("No route to host");
    }

    /**
     * 获取重试配置信息
     */
    public RetryConfig getRetryConfig() {
        return new RetryConfig(networkOptimized, retryAttempts, retryDelay, networkTimeout, forceSyncEnabled);
    }

    /**
     * 重试配置类
     */
    public static class RetryConfig {
        public final boolean networkOptimized;
        public final int retryAttempts;
        public final long retryDelay;
        public final long networkTimeout;
        public final boolean forceSyncEnabled;

        public RetryConfig(boolean networkOptimized, int retryAttempts, long retryDelay,
                           long networkTimeout, boolean forceSyncEnabled) {
            this.networkOptimized = networkOptimized;
            this.retryAttempts = retryAttempts;
            this.retryDelay = retryDelay;
            this.networkTimeout = networkTimeout;
            this.forceSyncEnabled = forceSyncEnabled;
        }
    }
}