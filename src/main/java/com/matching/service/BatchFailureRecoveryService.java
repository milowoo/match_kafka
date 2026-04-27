package com.matching.service;

import com.matching.config.RedisHealthIndicator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 批处理失败恢复服务 提供完整的批处理失败后的恢复机制，确保数据一致性
 */
@Service
@Slf4j
public class BatchFailureRecoveryService {

    @Value("${matching.batch.recovery.timeout-seconds:30}")
    private int recoveryTimeoutSeconds;

    @Value("${matching.batch.recovery.retry-attempts:3}")
    private int maxRetryAttempts;

    @Value("${matching.batch.recovery.retry-delay-ms:1000}")
    private long retryDelayMs;

    private final OrderBookService orderBookService;
    private final IdempotentService idempotentService;
    private final MatchingMetrics metrics;
    private final RedisHealthIndicator redisHealthIndicator;

    public BatchFailureRecoveryService(OrderBookService orderBookService,
                                       IdempotentService idempotentService,
                                       MatchingMetrics metrics,
                                       RedisHealthIndicator redisHealthIndicator) {
        this.orderBookService = orderBookService;
        this.idempotentService = idempotentService;
        this.metrics = metrics;
        this.redisHealthIndicator = redisHealthIndicator;
    }

    /**
     * 执行完整的批处理失败恢复
     */
    public void performRecovery(String symbolId, List<String> failedOrderIds, Exception originalError) {
        log.warn("[{}] Starting batch failure recovery for {} orders", symbolId, failedOrderIds.size());
        long startTime = System.currentTimeMillis();
        boolean recoverySuccess = false;

        try {
            executeRecoverySteps(symbolId, failedOrderIds, originalError);
            recoverySuccess = true;

            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > recoveryTimeoutSeconds * 1000L) {
                log.warn("[{}] Recovery succeeded but took {}ms (threshold: {}s)", symbolId, elapsed, recoveryTimeoutSeconds);
            } else {
                log.info("[{}] Batch failure recovery completed in {}ms", symbolId, elapsed);
            }
        } catch (Exception e) {
            log.error("[{}] Batch failure recovery failed", symbolId, e);
            performMinimalRecovery(symbolId, failedOrderIds);
        } finally {
            if (recoverySuccess) {
                metrics.recordBatchFailureRecovery(symbolId, failedOrderIds.size());
            } else {
                metrics.recordBatchFailureRecoveryFailed(symbolId, failedOrderIds.size());
            }
        }
    }

    private void executeRecoverySteps(String symbolId, List<String> failedOrderIds, Exception originalError) {
        // 步骤1：重新加载OrderBook
        reloadOrderBookWithRetry(symbolId);

        // 步骤2：清理幂等标记
        clearIdempotentMarksWithStrategy(symbolId, failedOrderIds);

        // 步骤3：验证恢复结果
        validateRecoveryResult(symbolId, failedOrderIds);
    }

    private void reloadOrderBookWithRetry(String symbolId) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            try {
                log.debug("[{}] Attempting OrderBook reload, attempt {}/{}", symbolId, attempt, maxRetryAttempts);
                orderBookService.reloadEngine(symbolId);
                log.info("[{}] OrderBook reloaded successfully on attempt {}", symbolId, attempt);
                return;
            } catch (Exception e) {
                lastException = e;
                log.warn("[{}] OrderBook reload failed on attempt {}: {}", symbolId, attempt, e.getMessage());
                if (attempt < maxRetryAttempts) {
                    try {
                        Thread.sleep(retryDelayMs * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Recovery interrupted", ie);
                    }
                }
            }
        }
        throw new RuntimeException("Failed to reload OrderBook after " + maxRetryAttempts + " attempts", lastException);
    }

    private void clearIdempotentMarksWithStrategy(String symbolId, List<String> failedOrderIds) {
        if (failedOrderIds.isEmpty()) {
            return;
        }

        String[] orderIdArray = failedOrderIds.toArray(new String[0]);
        boolean redisHealthy = redisHealthIndicator.isHealthy();

        log.debug("[{}] Clearing idempotent marks, Redis healthy: {}, orders: {}", symbolId, redisHealthy, failedOrderIds.size());

        if (redisHealthy) {
            try {
                idempotentService.clearAllMarks(symbolId, orderIdArray);
                log.info("[{}] Successfully cleared all idempotent marks (local + Redis)", symbolId);
            } catch (Exception e) {
                log.warn("[{}] Failed to clear Redis marks, falling back to local-only", symbolId, e);
                idempotentService.clearLocalMarks(symbolId, orderIdArray);
            }
        } else {
            log.warn("[{}] Redis unhealthy, only cleared local marks. Orders may be rejected until TTL expires.", symbolId);
            idempotentService.clearLocalMarks(symbolId, orderIdArray);
        }
    }

    private void validateRecoveryResult(String symbolId, List<String> failedOrderIds) {
        try {
            if (!orderBookService.exists(symbolId)) {
                throw new RuntimeException("OrderBook does not exist after recovery");
            }

            int localCacheSize = idempotentService.getLocalCacheSize(symbolId);
            log.debug("[{}] Recovery validation: local cache size = {}", symbolId, localCacheSize);

            if (localCacheSize > 10000) {
                log.warn("[{}] Local idempotent cache size is large: {}, consider cleanup", symbolId, localCacheSize);
            }

            log.info("[{}] Recovery validation passed", symbolId);
        } catch (Exception e) {
            log.error("[{}] Recovery validation failed", symbolId, e);
            throw new RuntimeException("Recovery validation failed", e);
        }
    }

    private void performMinimalRecovery(String symbolId, List<String> failedOrderIds) {
        log.warn("[{}] Performing minimal recovery for {} orders", symbolId, failedOrderIds.size());
        try {
            if (!failedOrderIds.isEmpty()) {
                String[] orderIdArray = failedOrderIds.toArray(new String[0]);
                idempotentService.clearLocalMarks(symbolId, orderIdArray);
                log.info("[{}] Minimal recovery: cleared local idempotent marks", symbolId);
            }
            try {
                orderBookService.reloadEngine(symbolId);
                log.info("[{}] Minimal recovery: OrderBook reloaded", symbolId);
            } catch (Exception e) {
                log.error("[{}] Minimal recovery: OrderBook reload failed", symbolId, e);
            }
        } catch (Exception e) {
            log.error("[{}] Even minimal recovery failed", symbolId, e);
        }
    }

    public RecoveryConfig getRecoveryConfig() {
        return new RecoveryConfig(recoveryTimeoutSeconds, maxRetryAttempts, retryDelayMs);
    }

    public static class RecoveryConfig {
        public final int timeoutSeconds;
        public final int maxRetryAttempts;
        public final long retryDelayMs;

        public RecoveryConfig(int timeoutSeconds, int maxRetryAttempts, long retryDelayMs) {
            this.timeoutSeconds = timeoutSeconds;
            this.maxRetryAttempts = maxRetryAttempts;
            this.retryDelayMs = retryDelayMs;
        }

        @Override
        public String toString() {
            return String.format("RecoveryConfig{timeout=%ds, maxRetry=%d, retryDelay=%dms}",
                    timeoutSeconds, maxRetryAttempts, retryDelayMs);
        }
    }
}