package com.matching.mq;

import com.matching.service.EventLog;
import com.matching.service.EventLogReplicationService;
import com.matching.service.HAService;
import com.matching.util.ProtoConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 从实例消费主实例复制的EventLog事件，写入本地Chronicle Queue。
 * 跳过条件：1) 自己发出的消息 (sourceInstanceId == 本实例) 2) 当前是PRIMARY角色
 */
@Slf4j
@Component
public class EventLogReplicationConsumer {

    private final EventLog eventLog;
    private final HAService haService;

    @Value("${matching.ha.instance-id:node-1}")
    private String instanceId;

    public EventLogReplicationConsumer(EventLog eventLog, HAService haService) {
        this.eventLog = eventLog;
        this.haService = haService;
    }

    @KafkaListener(
            topics = "${matching.kafka.topic.eventlog-sync:MATCHING_EVENTLOG_SYNC}",
            groupId = "${matching.ha.instance-id:node-1}-eventlog-sync",
            containerFactory = "replicationContainerFactory"
    )
    public void onEventLogSync(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        // 过滤1：自己发出的消息直接跳过（防止切换过程中两个都是STANDBY时自己消费自己）
        String sourceInstance = extractSourceInstance(record);
        if (instanceId.equals(sourceInstance)) {
            ack.acknowledge();
            return;
        }

        // 过滤2：PRIMARY角色不消费
        if (haService.isPrimary()) {
            ack.acknowledge();
            return;
        }

        try {
            EventLog.Event event = ProtoConverter.deserializeEvent(record.value());
            
            // 记录处理前的globalSeq
            long beforeSeq = eventLog.currentSeq();
            
            // 写入复制的EventLog(保留主实例的原始seq)
            eventLog.appendReplicated(event);
            
            // 记录处理后的globalSeq
            long afterSeq = eventLog.currentSeq();
            
            ack.acknowledge();
            
            // 记录seq更新日志
            if (afterSeq > beforeSeq) {
                log.info("[ReplicationConsumer] Updated globalSeq: {} -> {} (event seq: {} symbol: {} from: {})",
                        beforeSeq, afterSeq, event.getSeq(), event.getSymbolId(), sourceInstance);
            } else {
                log.debug("[ReplicationConsumer] Replicated event seq={} symbol={} from={} (globalSeq unchanged: {})",
                        event.getSeq(), event.getSymbolId(), sourceInstance, afterSeq);
            }
        } catch (Exception e) {
            log.error("[ReplicationConsumer] Failed to process replicated event, key={} from={}",
                    record.key(), sourceInstance, e);
            ack.acknowledge();
        }
    }

    private String extractSourceInstance(ConsumerRecord<String, byte[]> record) {
        Header header = record.headers().lastHeader(EventLogReplicationService.HEADER_SOURCE_INSTANCE);
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return "";
    }
}