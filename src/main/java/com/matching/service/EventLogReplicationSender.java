package com.matching.service;

import com.matching.util.ProtoConverter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EventLog复制发送服务
 * 负责将主实例的EventLog批量发送到Kafka，确保从实例能够获取完整的历史数据
 */
@Service
public class EventLogReplicationSender {
    private static final Logger log = LoggerFactory.getLogger(EventLogReplicationSender.class);

    // Redis全局key：记录已发送的最大序列号
    public static final String SENT_SEQ_KEY = "matching:eventlog:sent:seq";

    private final EventLog eventLog;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final String topic;

    @Value("${matching.replication.message-key:eventlog-sync}")
    private String messageKey;

    @Value("${matching.ha.instance-id:node-1}")
    private String instanceId;

    @Value("${matching.replication.redis-flush-interval:500}")
    private int redisFlushInterval;

    // 发送进度跟踪
    private final AtomicLong lastSentSeq = new AtomicLong(0);
    private final AtomicLong lastRedisFlushSeq = new AtomicLong(0);
    private volatile long startSeq = 0;

    // 发送线程
    private volatile boolean running = false;
    private Thread sendingThread;

    public EventLogReplicationSender(EventLog eventLog,
                                   KafkaTemplate<String, byte[]> kafkaTemplate,
                                   StringRedisTemplate redisTemplate,
                                   @Value("${matching.kafka.topic.eventlog-sync:MATCHING_EVENTLOG_SYNC}") String topic) {
        this.eventLog = eventLog;
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
        this.topic = topic;
    }

    @PostConstruct
    public void init() {
        try {
            ValueOperations<String, String> valueOps = redisTemplate.opsForValue();
            if (valueOps != null) {
                String persistedSeq = valueOps.get(SENT_SEQ_KEY);
                if (persistedSeq != null) {
                    lastSentSeq.set(Long.parseLong(persistedSeq));
                    log.info("Restored last sent seq: {}", lastSentSeq.get());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to restore last sent seq from Redis, starting from 0", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        stopSending();
    }

    /**
     * 设置发送起始序列号
     */
    public void setStartSeq(long startSeq) {
        this.startSeq = startSeq;
        log.info("Set start seq to: {}", startSeq);
    }

    /**
     * 启动发送服务
     */
    public void startSending() {
        if (running) {
            log.warn("Sending service is already running");
            return;
        }

        running = true;
        sendingThread = new Thread(this::sendingLoop, "EventLogReplicationSender");
        sendingThread.setDaemon(true);
        sendingThread.start();

        log.info("EventLog replication sender started");
    }

    /**
     * 停止发送服务
     */
    public void stopSending() {
        running = false;
        if (sendingThread != null) {
            sendingThread.interrupt();
            try {
                sendingThread.join(5000); // 等待最多5秒
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sendingThread = null;
        }
        log.info("EventLog replication sender stopped");
    }

    /**
     * 发送剩余事件（切换时使用）
     */
    public void sendRemainingEvents(long targetSeq) {
        log.info("Sending remaining events from {} to {}", lastSentSeq.get() + 1, targetSeq);

        long fromSeq = lastSentSeq.get() + 1;
        if (fromSeq > targetSeq) return;

        List<EventLog.Event> events = eventLog.readEventsInRange(fromSeq, targetSeq);
        for (EventLog.Event event : events) {
            try {
                byte[] data = serializeEvent(event);
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, messageKey, data);
                record.headers().add(new RecordHeader(EventLogReplicationService.HEADER_SOURCE_INSTANCE, instanceId.getBytes(StandardCharsets.UTF_8)));
                kafkaTemplate.send(record);
                updateLastSentSeq(event.getSeq());
                log.debug("Sent remaining event seq: {}", event.getSeq());
            } catch (Exception e) {
                log.error("Failed to send remaining event seq: {}", event.getSeq(), e);
            }
        }
    }

    /**
     * 等待发送完成
     */
    public void waitForSendCompletion() {
        try {
            Thread.sleep(200); // 简化的等待逻辑
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取最后发送的序列号
     */
    public long getLastSentSeq() {
        return lastSentSeq.get();
    }

    /**
     * 等待 Kafka 集群就绪（通过 partitionsFor 获取 topic metadata）
     * 在 HA activate 过程中调用，确保复制管道就绪后才开始接受订单。
     *
     * @param timeoutMs 最大等待时间（毫秒）
     * @return true 表示 Kafka 就绪，false 表示超时
     */
    public boolean waitForKafkaReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                kafkaTemplate.partitionsFor(topic);
                log.info("[Sender] Kafka cluster ready for topic {}", topic);
                return true;
            } catch (Exception e) {
                log.debug("[Sender] Kafka not ready yet, retrying...");
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        log.warn("[Sender] Kafka cluster not ready within {}ms for topic {}", timeoutMs, topic);
        return false;
    }

    /**
     * 检查重试队列是否为空，用于 HAService activate 时确认积压已清空
     */
    public boolean isRetryQueueEmpty() {
        return eventLog.getMaxLocalSeq() <= lastSentSeq.get();
    }

    /**
     * 发送循环
     */
    private void sendingLoop() {
        log.info("Starting event log replication sending loop");

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                long beforeSend = System.currentTimeMillis();
                sendingLoopIteration();
                long elapsedMs = System.currentTimeMillis() - beforeSend;

                // 根据待发送事件数动态调整睡眠时间
                long pendingEvents = eventLog.getMaxLocalSeq() - lastSentSeq.get();

                // 计算动态睡眠时间
                // 无待发送事件：睡100ms (原始值)
                // 有小量待发送事件(1-10)：睡50ms
                // 有中量待发送事件(11-100)：睡20ms
                // 有大量待发送事件(>100)：睡10ms
                long sleepMs;
                if (pendingEvents == 0) {
                    sleepMs = 100;
                } else if (pendingEvents <= 10) {
                    sleepMs = 50;
                } else if (pendingEvents <= 100) {
                    sleepMs = 20;
                } else {
                    sleepMs = 10;
                }

                // 如果上一次迭代已经耗时很长，跳过睡眠
                if (elapsedMs > sleepMs) {
                    log.debug("Sending loop iteration took {}ms, skipping sleep", elapsedMs);
                } else {
                    Thread.sleep(sleepMs - elapsedMs);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in sending loop", e);
                try {
                    Thread.sleep(1000); // 错误后等待1秒
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("Event log replication sending loop stopped");
    }

    /**
     * 单次发送循环迭代
     */
    private void sendingLoopIteration() {
        long currentCommittedSeq = eventLog.getMaxLocalSeq();
        long currentLastSentSeq = lastSentSeq.get();

        if (currentCommittedSeq > currentLastSentSeq) {
            // 批量读取并发送新事件
            sendPendingEvents();
        }
    }

    /**
     * 发送待处理的事件
     */
    private void sendPendingEvents() {
        long currentCommittedSeq = eventLog.getMaxLocalSeq();
        long currentLastSentSeq = lastSentSeq.get();

        if (currentCommittedSeq <= currentLastSentSeq) return;

        // 单次扫描读取所有待发送事件，避免 O(n²) 逐条查询
        List<EventLog.Event> events = eventLog.readEventsInRange(currentLastSentSeq + 1, currentCommittedSeq);

        for (EventLog.Event event : events) {
            try {
                byte[] data = serializeEvent(event);
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, messageKey, data);
                record.headers().add(new RecordHeader(EventLogReplicationService.HEADER_SOURCE_INSTANCE, instanceId.getBytes(StandardCharsets.UTF_8)));
                kafkaTemplate.send(record);
                updateLastSentSeq(event.getSeq());
                log.debug("Sent event seq: {}", event.getSeq());
            } catch (Exception e) {
                log.error("Failed to send event seq: {}", event.getSeq(), e);
                // 继续处理下一个事件
            }
        }
    }

    /**
     * 更新最后发送的序列号。
     * 内存中 lastSentSeq 实时更新；Redis 每隔 redisFlushInterval 次写一次，
     * 减少 Redis 写入压力。重启后最多重复发送 redisFlushInterval 条事件，
     * 从实例的 appendReplicated 有 seq 幂等检查，重复发送无害。
     */
    private void updateLastSentSeq(long seq) {
        lastSentSeq.set(seq);

        // 每 N 次才写一次 Redis
        long lastFlush = lastRedisFlushSeq.get();
        if (seq - lastFlush < redisFlushInterval) {
            return;
        }
        if (!lastRedisFlushSeq.compareAndSet(lastFlush, seq)) {
            return;
        }

        try {
            redisTemplate.opsForValue().set(SENT_SEQ_KEY, String.valueOf(seq));
        } catch (Exception e) {
            log.warn("Failed to persist last sent seq: {}", seq, e);
        }
    }

    /**
     * 序列化Event为byte数组
     */
    private byte[] serializeEvent(EventLog.Event event) {
        try {
            return ProtoConverter.serializeEvent(event);
        } catch (Exception e) {
            log.error("Failed to serialize event seq: {}", event.getSeq(), e);
            throw new RuntimeException("Failed to serialize event", e);
        }
    }
}
