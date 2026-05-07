package com.matching.mq;

import com.matching.disruptor.DisruptorManager;
import com.matching.dto.*;
import com.matching.enums.TradeCommandType;
import com.matching.proto.MatchingProto.*;
import com.matching.service.DeadLetterService;
import com.matching.service.HAService;
import com.matching.service.TraceContext;
import com.matching.service.KafkaConsumerStartupService;
import com.matching.util.ProtoConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class ReceiverMq {

    @Value("${matching.ha.role:STANDBY}")
    private String initialRole;

    @Value("${matching.kafka.consumer.auto-startup:true}")
    private boolean autoStartup;

    private final DisruptorManager disruptorManager;
    private final DeadLetterService deadLetterService;
    private final KafkaConsumerStartupService kafkaConsumerStartupService;
    private final HAService haService;

    public ReceiverMq(DisruptorManager disruptorManager,
                      DeadLetterService deadLetterService,
                      KafkaConsumerStartupService kafkaConsumerStartupService,
                      HAService haService) {
        this.disruptorManager = disruptorManager;
        this.deadLetterService = deadLetterService;
        this.kafkaConsumerStartupService = kafkaConsumerStartupService;
        this.haService = haService;
    }

    @PostConstruct
    public void init() {
        disruptorManager.setResultSplitter(this::splitAndSerialize);
        log.info("ReceiverMq initialized - initialRole: {}, autoStartup: {}", initialRole, autoStartup);
        if (!autoStartup) {
            log.warn("Kafka consumer auto-startup is disabled. Consumers will be managed by KafkaConsumerStartupService.");
        }
    }

    @KafkaListener(
        topics = "${matching.kafka.topic.trade}",
        groupId = "${matching.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory",
        autoStartup = "${matching.kafka.consumer.auto-startup:true}"
    )
    public void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        // 双重检查：shouldBeRunning 和 isActive 必须同时为 true 才处理
        // 修复隐患6：回滚时 stopConsumers 失败，Kafka 消费者仍在运行但 shouldBeRunning=false
        // 原来直接 ack 丢弃会导致 offset 提交但消息未处理（数据丢失）
        // 现在不 ack，让 Kafka 重投，确保消息不丢失
        if (!kafkaConsumerStartupService.shouldBeRunning() || !haService.isActive()) {
            log.debug("[ReceiverMq] Not ready to process, shouldBeRunning={}, isActive={}, offset={}",
                    kafkaConsumerStartupService.shouldBeRunning(), haService.isActive(), record.offset());
            return;  // 不 ack，Kafka 会重投该消息
        }

        TraceContext.start(record.key(), String.valueOf(record.offset()));
        try {
            PbTradeCommand pbCmd = PbTradeCommand.parseFrom(record.value());
            TradeCommandType type = TradeCommandType.valueOf(pbCmd.getType());

            switch (type) {
                case PLACE_ORDER:
                    PlaceOrderParam placeParam = ProtoConverter.fromProto(PbPlaceOrderParam.parseFrom(pbCmd.getPayload()));
                    if (placeParam.getOrderList() == null || placeParam.getOrderList().isEmpty()) {
                        ack.acknowledge();
                        return;
                    }
                    disruptorManager.publishPlaceOrder(placeParam.getSymbolId(), placeParam, ack, null);
                    break;
                case CANCEL_ORDER:
                    CancelOrderParam cancelParam = ProtoConverter.fromProto(PbCancelOrderParam.parseFrom(pbCmd.getPayload()));
                    disruptorManager.publishCancelOrder(cancelParam.getSymbolId(), cancelParam, ack, null);
                    break;
                default:
                    log.warn("Unknown command type: {}", pbCmd.getType());
                    ack.acknowledge();
            }
        } catch (Exception e) {
            log.error("Failed to deserialize protobuf message, sending to DLQ. offset: {}", record.offset(), e);
            deadLetterService.send(record.key(),
                java.util.Base64.getEncoder().encodeToString(record.value()), e.getMessage());
            ack.acknowledge();
        } finally {
            TraceContext.clear();
        }
    }

    private Map<String, byte[]> splitAndSerialize(String symbolId, MatchResult matchResult) {
        Map<Long, MatchResult> byUid = splitByUid(symbolId, matchResult);
        Map<String, byte[]> result = new HashMap<>();
        for (Map.Entry<Long, MatchResult> entry : byUid.entrySet()) {
            result.put(String.valueOf(Long.hashCode(entry.getKey())), ProtoConverter.serializeMatchResult(entry.getValue()));
        }
        return result;
    }

    /**
     * 按 uid 分组，Kafka 消息 key = uid，下游按 uid hash 路由到对应的 trade-engine 分片。
     * uid 必填，已由 OrderValidator 拦截，此处 uid 为 null 属异常情况直接跳过并告警。
     */
    private Map<Long, MatchResult> splitByUid(String symbolId, MatchResult matchResult) {
        Map<Long, MatchResult> map = new HashMap<>();
        for (MatchCreateOrderResult co : matchResult.getCreateOrders()) {
            if (co.getUid() == null) {
                log.warn("[{}] MatchCreateOrderResult missing uid, accountId={}, skipping", symbolId, co.getAccountId());
                continue;
            }
            map.computeIfAbsent(co.getUid(), k -> MatchResult.builder().symbolId(symbolId).build())
                .getCreateOrders().add(co);
        }
        for (MatchDealtResult dr : matchResult.getDealtRecords()) {
            if (dr.getUid() == null) {
                log.warn("[{}] MatchDealtResult missing uid, accountId={}, skipping", symbolId, dr.getAccountId());
                continue;
            }
            map.computeIfAbsent(dr.getUid(), k -> MatchResult.builder().symbolId(symbolId).build())
                .getDealtRecords().add(dr);
        }
        for (MatchOrderResult or : matchResult.getDealtOrders()) {
            if (or.getUid() == null) {
                log.warn("[{}] MatchOrderResult missing uid, accountId={}, skipping", symbolId, or.getAccountId());
                continue;
            }
            map.computeIfAbsent(or.getUid(), k -> MatchResult.builder().symbolId(symbolId).build())
                .getDealtOrders().add(or);
        }
        return map;
    }
}