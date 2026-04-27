package com.matching.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Slf4j
public class DeadLetterService {

    @Value("${matching.kafka.topic.dlq:TRADE_MATCHING_DLQ}")
    private String dlqTopic;

    private final KafkaTemplate<String, String> kafkaTemplate;

    public DeadLetterService(@org.springframework.beans.factory.annotation.Qualifier("dlqKafkaTemplate")
                              KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void send(String key, String value, String reason) {
        try {
            Map<String, Object> dlq = new LinkedHashMap<>();
            dlq.put("reason", reason);
            dlq.put("key", key);
            dlq.put("value", value);
            dlq.put("timestamp", System.currentTimeMillis());
            String dlqMessage = com.matching.util.JsonUtil.serialize(dlq);

            kafkaTemplate.send(dlqTopic, key, dlqMessage)
                    .whenComplete((SendResult<String, String> result, Throwable ex) -> {
                        if (ex == null) {
                            log.warn("Message sent to DLQ, key: {}, reason: {}", key, reason);
                        } else {
                            log.error("Failed to send to DLQ, key: {}", key, ex);
                        }
                    });
        } catch (Exception e) {
            log.error("Failed to build DLQ message, key: {}", key, e);
        }
    }
}