package com.matching.service.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 重试队列管理器 - 负责管理重试队列和紧急队列
 */
@Component
@Slf4j
public class RetryQueueManager {

    @Value("${matching.outbox.max-retry-queue-size:100000}")
    private int maxRetryQueueSize;

    @Value("${matching.outbox.block-on-full-queue:true}")
    private boolean blockOnFullQueue;

    @Value("${matching.outbox.max-block-time-ms:30000}")
    private long maxBlockTimeMs;

    @Value("${matching.outbox.emergency-queue-size:1000}")
    private int emergencyQueueSize;

    private static final int DEFAULT_MAX_RETRY_QUEUE_SIZE = 100000;

    private final ConcurrentLinkedQueue<OutboxEntry> retryQueue = new ConcurrentLinkedQueue<>();
    private BlockingQueue<OutboxEntry> persistentFailureQueue;
    private final FailureLogPersister failureLogPersister;
    private final AlertService alertService;

    public RetryQueueManager(FailureLogPersister failureLogPersister, AlertService alertService) {
        this.failureLogPersister = failureLogPersister;
        this.alertService = alertService;
        init();
    }

    public void init() {
        int queueSize = emergencyQueueSize > 0 ? emergencyQueueSize : 1000;
        this.persistentFailureQueue = new LinkedBlockingQueue<>(queueSize);
        log.info("Initialized RetryQueueManager with emergency queue size: {}", queueSize);
    }

    /**
     * 添加到重试队列 - 绝不丢失数据的安全版本
     */
    public void addToRetryQueue(OutboxEntry entry) {
        int currentMaxSize = maxRetryQueueSize > 0 ? maxRetryQueueSize : DEFAULT_MAX_RETRY_QUEUE_SIZE;

        if (retryQueue.size() >= currentMaxSize) {
            handleFullRetryQueue(entry, currentMaxSize);
        } else {
            retryQueue.add(entry);
        }
    }

    /**
     * 处理重试队列满的情况 - 多层保护机制
     */
    private void handleFullRetryQueue(OutboxEntry entry, int maxSize) {
        // 第一层保护: 尝试持久化到磁盘
        if (tryPersistToFailureLog(entry)) {
            alertService.sendAlert("retryQueue full, data persisted to failure log", entry.getUidKey());
            return;
        }

        // 第二层保护: 尝试添加到持久化失败队列
        if (persistentFailureQueue.offer(entry)) {
            alertService.sendAlert("retryQueue full, added to emergency queue", entry.getUidKey());
            return;
        }

        // 第三层保护: 阻塞等待(如果启用)
        if (blockOnFullQueue) {
            try {
                if (blockingWaitForSpace(entry, maxSize)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for space in retry queue", e);
            }
        }

        // 第四层保护: 强制扩容(紧急情况)
        forceAddToQueue(entry, maxSize);
        alertService.sendCriticalAlert("FORCED QUEUE EXPANSION", entry.getUidKey());
    }

    private boolean tryPersistToFailureLog(OutboxEntry entry) {
        try {
            failureLogPersister.persistFailureLog(entry.getUidKey(), entry.getPayload());
            return true;
        } catch (Exception e) {
            log.error("[CRITICAL] Failed to persist to failure log: {}", entry.getUidKey(), e);
            return false;
        }
    }

    private boolean blockingWaitForSpace(OutboxEntry entry, int maxSize) throws InterruptedException {
        log.warn("[BLOCKING] Retry queue full ({}), blocking to prevent data loss: {}",
                maxSize, entry.getUidKey());

        long startTime = System.currentTimeMillis();
        long endTime = startTime + maxBlockTimeMs;

        while (System.currentTimeMillis() < endTime) {
            if (retryQueue.size() < maxSize) {
                retryQueue.add(entry);
                long waitTime = System.currentTimeMillis() - startTime;
                log.info("[RECOVERED] Queue space available after {}ms, added entry: {}", waitTime, entry.getUidKey());
                return true;
            }
            Thread.sleep(100);
        }

        log.error("[BLOCKING] Blocking wait timed out after {}ms", maxBlockTimeMs);
        return false;
    }

    private void forceAddToQueue(OutboxEntry entry, int maxSize) {
        OutboxEntry oldestEntry = retryQueue.poll();
        if (oldestEntry != null) {
            if (tryPersistToFailureLog(oldestEntry)) {
                alertService.sendCriticalAlert("FORCED DATA DISPLACEMENT", oldestEntry.getUidKey());
            }
        }

        retryQueue.add(entry);
        alertService.sendCriticalAlert("FORCED QUEUE EXPANSION", entry.getUidKey());
    }

    public ConcurrentLinkedQueue<OutboxEntry> getRetryQueue() {
        return retryQueue;
    }

    public BlockingQueue<OutboxEntry> getPersistentFailureQueue() {
        return persistentFailureQueue;
    }

    /**
     * 处理持久化失败队列
     */
    public boolean processPersistentFailureQueue(int maxProcessPerCycle) {
        if (persistentFailureQueue.isEmpty()) return false;

        int processedCount = 0;
        int maxProcess = maxProcessPerCycle > 0 ? maxProcessPerCycle : 100;

        while (processedCount < maxProcess) {
            OutboxEntry entry = persistentFailureQueue.poll();
            if (entry == null) break;

            if (tryPersistToFailureLog(entry)) {
                processedCount++;
                log.info("Successfully persisted emergency queue entry: {}", entry.getUidKey());
            } else {
                if (!persistentFailureQueue.offer(entry)) {
                    if (!retryQueue.offer(entry)) {
                        alertService.sendCriticalAlert("EMERGENCY QUEUE OVERFLOW", entry.getUidKey());
                    }
                    break;
                }
            }
        }

        if (processedCount > 0) {
            log.info("Processed {} entries from emergency queue, {} remaining",
                    processedCount, persistentFailureQueue.size());
        }

        return processedCount > 0;
    }
}