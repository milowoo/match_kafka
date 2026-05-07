package com.matching.service;

import com.matching.util.ProtoConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 主实例将EventLog事件通过Kafka发送给从实例。策略：同步发送等待broker确认，如果失败则加入重试队列异步重试。
 * 确保从实例最终一定能收到所有事件，确保HA切换时数据一致性。
 */
@Slf4j
@Service
public class EventLogReplicationService {

    public static final String HEADER_SOURCE_INSTANCE = "sourceInstanceId";

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final BlockingQueue<PendingReplication> retryQueue;

    @Value("${matching.kafka.topic.eventlog-sync:MATCHING_EVENTLOG_SYNC}")
    private String eventlogSyncTopic;

    @Value("${matching.ha.instance-id:node-1}")
    private String instanceId;

    @Value("${matching.replication.send-timeout-ms:5000}")
    private long sendTimeoutMs;

    @Value("${matching.replication.message-key:eventlog-sync}")
    private String messageKey;

    private final int retryQueueMaxSize;

    public EventLogReplicationService(@Qualifier("reliableKafkaTemplate") KafkaTemplate<String, byte[]> kafkaTemplate,
                                       @Value("${matching.replication.retry-queue-max-size:100000}") int retryQueueMaxSize) {
        this.kafkaTemplate = kafkaTemplate;
        this.retryQueueMaxSize = retryQueueMaxSize;
        this.retryQueue = new LinkedBlockingQueue<>(retryQueueMaxSize);
    }

    /**
     * 同步发送EventLog复制消息。为了保证顺序性，如果重试队列不为空（有pending事件），
     * 则将新事件也加入重试队列，由后台线程按FIFO顺序重试。
     * 这样保证从实例收到的事件顺序与主实例一致。
     *
     * 当重试队列满时，会阻塞等待（put），通过背压反作用于撮合速度，确保不丢事件。
     */
    public void replicateEvent(EventLog.Event event) {
        byte[] data = ProtoConverter.serializeEvent(event);
        String symbolId = event.getSymbolId();
        long seq = event.getSeq();

        PendingReplication pending = new PendingReplication(symbolId, data, seq);

        // **关键**：如果重试队列不为空，说明有pending事件，为了保证顺序性，
        // 新事件也必须加入队列，等待后台线程按FIFO顺序处理
        if (!retryQueue.isEmpty()) {
            enqueueOrBlock(pending);
            return;
        }

        // 重试队列为空，直接尝试发送
        if (trySend(symbolId, data, seq)) {
            return; // 成功发送
        }

        // 发送失败，加入重试队列
        enqueueOrBlock(pending);
    }

    /**
     * 将事件加入重试队列。队列满时阻塞等待（put），确保不丢事件。
     * 通过背压自然限制主实例产生事件的速度，保护内存不会无限增长。
     */
    private void enqueueOrBlock(PendingReplication pending) {
        try {
            retryQueue.put(pending);
            log.debug("[Replication] Queued: seq={} symbol={}, queueSize={}",
                    pending.seq, pending.symbolId, retryQueue.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Replication] Interrupted while enqueueing event seq={}, event may be lost", pending.seq, e);
        }
    }

    private boolean trySend(String symbolId, byte[] data, long seq) {
        try {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(eventlogSyncTopic, messageKey, data);
            record.headers().add(new RecordHeader(HEADER_SOURCE_INSTANCE, instanceId.getBytes(StandardCharsets.UTF_8)));
            kafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            log.debug("[Replication] Sent event seq={} symbol={} instance={} size={}bytes",
                    seq, symbolId, instanceId, data.length);
            return true;
        } catch (Exception e) {
            log.warn("[Replication] Failed to send event seq={} symbol={}, will retry async", seq, symbolId, e);
            return false;
        }
    }

    /**
     * 后台定时重试失败的复制消息，每3秒执行一次。
     * 按FIFO顺序重试，确保事件顺序性。
     */
    @Scheduled(fixedDelay = 3000, initialDelay = 3000)
    public void retryPending() {
        if (retryQueue.isEmpty()) return;

        int retried = 0;
        int maxPerCycle = 100; // 每次最多重试100个，避免阻塞太久

        while (retried < maxPerCycle) {
            PendingReplication pending = retryQueue.peek();
            if (pending == null) break;

            if (trySend(pending.symbolId, pending.data, pending.seq)) {
                retryQueue.poll(); // 成功才移除
                retried++;
            } else {
                // 发送仍失败，保留在队列中等待下次重试
                break;
            }
        }

        if (retried > 0) {
            log.info("[Replication] Retried {} events, {} remaining in queue", retried, retryQueue.size());
        }
    }

    public int getRetryQueueSize() {
        return retryQueue.size();
    }

    private static class PendingReplication {
        final String symbolId;
        final byte[] data;
        final long seq;

        PendingReplication(String symbolId, byte[] data, long seq) {
            this.symbolId = symbolId;
            this.data = data;
            this.seq = seq;
        }
    }
}