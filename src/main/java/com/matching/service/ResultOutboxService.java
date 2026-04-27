package com.matching.service;

import com.matching.config.KafkaTimeoutConfig;
import com.matching.ha.InstanceLeaderElection;
import com.matching.service.outbox.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.api.list.MutableList;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class ResultOutboxService {

    @Value("${matching.kafka.topic.result}")
    private String matchResultTopic;

    @Value("${matching.outbox.max-process-per-cycle:100}")
    private int maxProcessPerCycle;

    @Value("${matching.kafka.send-timeout-ms:5000}")
    private int kafkaSendTimeoutMs;

    private final KafkaTimeoutConfig timeoutConfig;
    private final KafkaTimeoutMonitorService timeoutMonitorService;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final InstanceLeaderElection leaderElection;
    private final RetryQueueManager retryQueueManager;
    private final AlertService alertService;

    public ResultOutboxService(@Qualifier("reliableKafkaTemplate")
                                    KafkaTemplate<String, byte[]> kafkaTemplate,
                                InstanceLeaderElection leaderElection,
                                RetryQueueManager retryQueueManager,
                                AlertService alertService,
                                KafkaTimeoutConfig timeoutConfig,
                                KafkaTimeoutMonitorService timeoutMonitorService) {
        this.kafkaTemplate = kafkaTemplate;
        this.leaderElection = leaderElection;
        this.retryQueueManager = retryQueueManager;
        this.alertService = alertService;
        this.timeoutConfig = timeoutConfig;
        this.timeoutMonitorService = timeoutMonitorService;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        retryQueueManager.init();
        log.info("ResultOutboxService initialized");
    }

    public void sendBatch(List<OutboxEntry> entries, Runnable onComplete) {
        if (entries == null || entries.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        MutableList<OutboxEntry> fastEntries = entries instanceof FastList ?
                (FastList<OutboxEntry>) entries : new FastList<>(entries);

        CountDownLatch latch = new CountDownLatch(fastEntries.size());
        AtomicBoolean hasFailed = new AtomicBoolean(false);

        fastEntries.forEach(entry -> sendSingleEntry(entry, latch, hasFailed));

        CompletableFuture.runAsync(() -> {
            try {
                latch.await();
                // Always run onComplete to update lastSentSeq.
                // Failed entries are already in retryQueue and will be retried.
                // LastSentSeq tracks progress, not Kafka delivery.
                if (onComplete != null) {
                    onComplete.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for batch send completion", e);
            }
        });
    }

    private void sendSingleEntry(OutboxEntry entry, CountDownLatch latch, AtomicBoolean hasFailed) {
        try {
            byte[] payload = entry.getPayload();
            if (payload == null || payload.length == 0) {
                log.warn("Empty payload for account key: {}", entry.getUidKey());
                latch.countDown();
                return;
            }

            kafkaTemplate.send(matchResultTopic, entry.getUidKey(), payload)
                    .whenComplete((sendResult, ex) -> {
                        try {
                            if (ex != null) {
                                hasFailed.set(true);
                                retryQueueManager.addToRetryQueue(entry);
                                log.warn("Kafka send failed, added to retry queue, key: {}", entry.getUidKey(), ex);
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        } catch (Exception e) {
            hasFailed.set(true);
            retryQueueManager.addToRetryQueue(entry);
            log.warn("Kafka send exception, added to retry queue, key: {}", entry.getUidKey(), e);
            latch.countDown();
        }
    }

    public void enqueueForRetry(List<OutboxEntry> entries) {
        if (entries == null || entries.isEmpty()) return;

        for (OutboxEntry entry : entries) {
            retryQueueManager.addToRetryQueue(entry);
        }
        log.info("Enqueued {} recovered match results for retry", entries.size());
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    public void retryPending() {
        if (!leaderElection.isActive()) return;

        retryQueueManager.processPersistentFailureQueue(maxProcessPerCycle);
        processRetryQueue();
    }

    private void processRetryQueue() {
        var retryQueue = retryQueueManager.getRetryQueue();
        if (retryQueue.isEmpty()) return;

        int count = 0;
        int maxRetryPerCycle = 200;

        while (count < maxRetryPerCycle) {
            OutboxEntry entry = retryQueue.poll();
            if (entry == null) break;

            try {
                timeoutMonitorService.recordSendAttempt();
                byte[] payload = entry.getPayload();
                if (payload == null || payload.length == 0) {
                    log.warn("Empty payload for account key: {}, skipping", entry.getUidKey());
                    continue;
                }

                kafkaTemplate.send(matchResultTopic, entry.getUidKey(), payload)
                        .get(timeoutConfig.getSyncSendTimeoutMs(), TimeUnit.MILLISECONDS);

                timeoutMonitorService.recordSuccess();
                count++;
            } catch (Exception e) {
                timeoutMonitorService.recordFailedAttempt(entry.getUidKey(), e);
                retryQueue.add(entry);
                log.warn("Retry send failed, {} remaining in queue", retryQueue.size(), e);
                break;
            }
        }

        if (count > 0) {
            var persistentQueue = retryQueueManager.getPersistentFailureQueue();
            log.info("Retry sent {} match results, {} remaining in retry queue, {} in emergency queue",
                    count, retryQueue.size(), persistentQueue.size());
        }
    }

    public RetryQueueStatus getRetryQueueStatus() {
        var retryQueue = retryQueueManager.getRetryQueue();
        var persistentQueue = retryQueueManager.getPersistentFailureQueue();

        return new RetryQueueStatus(
                retryQueue != null ? retryQueue.size() : 0,
                persistentQueue != null ? persistentQueue.size() : 0,
                alertService.getAlertCount(),
                alertService.getLastAlertTime(),
                true,
                100000,
                "/tmp/matching/failure-log",
                true,
                true,
                30000L
        );
    }
}