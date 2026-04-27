package com.matching.service;

import com.matching.util.ProtoConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    // 发送进度跟踪
    private final AtomicLong lastSentSeq = new AtomicLong(0);
    private volatile long startSeq = 0;

    // 发送线程
    private volatile boolean running = false;
    private Thread sendingThread;

    // 异步Redis更新线程池
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public EventLogReplicationSender(EventLog eventLog,
                                   KafkaTemplate<String, byte[]> kafkaTemplate,
                                   StringRedisTemplate redisTemplate,
                                   String topic) {
        this.eventLog = eventLog;
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
        this.topic = topic;
    }

    @PostConstruct
    public void init() {
        // 从Redis恢复发送进度
        String persistedSeq = redisTemplate.opsForValue().get(SENT_SEQ_KEY);
        if (persistedSeq != null) {
            lastSentSeq.set(Long.parseLong(persistedSeq));
            log.info("Restored last sent seq: {}", lastSentSeq.get());
        }
    }

    @PreDestroy
    public void shutdown() {
        stopSending();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
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

        for (long seq = lastSentSeq.get() + 1; seq <= targetSeq; seq++) {
            try {
                EventLog.Event event = readEventBySeq(seq);
                if (event != null) {
                    byte[] data = serializeEvent(event);
                    kafkaTemplate.send(topic, String.valueOf(seq), data);
                    updateLastSentSeq(seq);
                    log.debug("Sent remaining event seq: {}", seq);
                } else {
                    log.warn("Event not found for seq: {}", seq);
                }
            } catch (Exception e) {
                log.error("Failed to send remaining event seq: {}", seq, e);
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

        // 发送从lastSentSeq+1到currentCommittedSeq的所有事件
        for (long seq = currentLastSentSeq + 1; seq <= currentCommittedSeq; seq++) {
            try {
                EventLog.Event event = readEventBySeq(seq);
                if (event != null) {
                    byte[] data = serializeEvent(event);
                    kafkaTemplate.send(topic, String.valueOf(seq), data);
                    updateLastSentSeq(seq);
                    log.debug("Sent event seq: {}", seq);
                } else {
                    log.warn("Event not found for seq: {} during batch send", seq);
                }
            } catch (Exception e) {
                log.error("Failed to send event seq: {}", seq, e);
                // 继续处理下一个事件
            }
        }
    }

    /**
     * 更新最后发送的序列号
     */
    private void updateLastSentSeq(long seq) {
        lastSentSeq.set(seq);
        // 异步更新Redis
        executorService.submit(() -> {
            try {
                redisTemplate.opsForValue().set(SENT_SEQ_KEY, String.valueOf(seq));
            } catch (Exception e) {
                log.warn("Failed to persist last sent seq: {}", seq, e);
            }
        });
    }

    /**
     * 从EventLog中读取指定seq的事件
     */
    private EventLog.Event readEventBySeq(long seq) {
        try {
            return eventLog.readEventBySeq(seq);
        } catch (Exception e) {
            log.error("Failed to read event by seq: {}", seq, e);
            return null;
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
