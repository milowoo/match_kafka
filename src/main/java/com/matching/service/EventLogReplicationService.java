package com.matching.service;

import com.matching.util.ProtoConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * 主实例将EventLog事件通过Kafka发送给从实例。策略：同步发送等待broker确认，如果失败，事件进入内存重试队列，后台定时线程持续重试直到成功。
 * 这样既不阻塞融合线程，又保证从实例最终一定能收到所有事件。基础情况（OOM等极端队列丢失）：从实例在failover时通过Redis Snapshot + 本地EventLog回放恢复，不依赖重试队列。
 */
@Slf4j
@Service
public class EventLogReplicationService {

    public static final String HEADER_SOURCE_INSTANCE = "sourceInstanceId";

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ConcurrentLinkedQueue<PendingReplication> retryQueue = new ConcurrentLinkedQueue<>();

    @Value("${matching.kafka.topic.eventlog-sync:MATCHING_EVENTLOG_SYNC}")
    private String eventlogSyncTopic;

    @Value("${matching.ha.instance-id:node-1}")
    private String instanceId;

    @Value("${matching.replication.send-timeout-ms:5000}")
    private long sendTimeoutMs;

    @Value("${matching.replication.retry-queue-max-size:100000}")
    private int retryQueueMaxSize;

    public EventLogReplicationService(@Qualifier("reliableKafkaTemplate") KafkaTemplate<String, byte[]> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 同步发送EventLog复制消息。失败时加入重试队列，不抛异常，调用方（flush）可以安全地继续ack。
     */
    public void replicateEvent(EventLog.Event event) {
        byte[] data = ProtoConverter.serializeEvent(event);
        if (trySend(event.getSymbolId(), data, event.getSeq())) {
            return;
        }
        // 发送失败，加入重试队列
        if (retryQueue.size() < retryQueueMaxSize) {
            retryQueue.add(new PendingReplication(event.getSymbolId(), data, event.getSeq()));
            log.warn("[Replication] Queued for retry: seq={} symbol={}, retryQueueSize={}",
                    event.getSeq(), event.getSymbolId(), retryQueue.size());
        } else {
            log.error("[Replication] Retry queue full ({}), dropping event seq={} symbol={}. " +
                    "Standby will recover via Snapshot + local EventLog on failover.",
                    retryQueueMaxSize, event.getSeq(), event.getSymbolId());
        }
    }

    private boolean trySend(String symbolId, byte[] data, long seq) {
        try {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(eventlogSyncTopic, symbolId, data);
            record.headers().add(new RecordHeader(HEADER_SOURCE_INSTANCE, instanceId.getBytes(StandardCharsets.UTF_8)));
            kafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            log.debug("[Replication] Sent event seq={} symbol={} instance={} size={}bytes",
                    seq, symbolId, instanceId, data.length);
            return true;
        } catch (Exception e) {
            log.error("[Replication] Failed to send event seq={} symbol={}", seq, symbolId, e);
            return false;
        }
    }

    /**
     * 后台定时重试失败的复制消息，每3秒执行一次。
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void retryPending() {
        if (retryQueue.isEmpty()) return;

        int retried = 0;
        int maxPerCycle = 500;

        while (retried < maxPerCycle) {
            PendingReplication pending = retryQueue.peek();
            if (pending == null) break;

            if (trySend(pending.symbolId, pending.data, pending.seq)) {
                retryQueue.poll(); // 成功才移除
                retried++;
            } else {
                break; // Kafka仍不可用，等下一轮
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