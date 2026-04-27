package com.matching.service.depth;

import com.matching.config.KafkaTimeoutConfig;
import com.matching.model.DepthUpdate;
import com.matching.proto.MatchingProto.PbDepthUpdate;
import com.matching.util.ProtoConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class DepthSender {

    @Value("${matching.depth.topic:depth-updates}")
    private String depthTopic;

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final KafkaTimeoutConfig timeoutConfig;

    private final AtomicLong totalPublished = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);

    public DepthSender(@org.springframework.beans.factory.annotation.Qualifier("fastKafkaTemplate")
                       KafkaTemplate<String, byte[]> kafkaTemplate,
                       KafkaTimeoutConfig timeoutConfig) {
        this.kafkaTemplate = kafkaTemplate;
        this.timeoutConfig = timeoutConfig;
    }

    public void sendSingleUpdate(DepthUpdate update) {
        try {
            PbDepthUpdate pbUpdate = ProtoConverter.convertDepthUpdate(update);
            byte[] data = pbUpdate.toByteArray();

            kafkaTemplate.send(depthTopic, update.getSymbol(), data)
                    .whenComplete((SendResult<String, byte[]> result, Throwable ex) -> {
                        if (ex != null) {
                            log.error("深度更新发送失败: {}", update.getSymbol(), ex);
                            totalErrors.incrementAndGet();
                        }
                    });

            totalPublished.incrementAndGet();
        } catch (Exception e) {
            log.error("发送深度更新失败: {}", update.getSymbol(), e);
            totalErrors.incrementAndGet();
            throw e;
        }
    }

    public long getTotalPublished() {
        return totalPublished.get();
    }

    public long getTotalErrors() {
        return totalErrors.get();
    }
}