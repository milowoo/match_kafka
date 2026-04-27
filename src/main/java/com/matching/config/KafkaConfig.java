package com.matching.config;

import com.matching.disruptor.DisruptorManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Configuration
@Slf4j
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:matching-engine}")
    private String groupId;

    @Value("${spring.kafka.listener.concurrency:4}")
    private int concurrency;

    @Bean
    public ConsumerFactory<String, byte[]> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);  // 增加到1000
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600_000);
        props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");

        // 优化配置：降低poll等待时间，提升响应性
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 50);   // 从默认500ms降低到50ms
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1024);   // 最小拉取字节数

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean("kafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> kafkaListenerContainerFactory(
            DisruptorManager disruptorManager) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setConsumerRebalanceListener(new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                if (!partitions.isEmpty()) {
                    log.info("Partitions revoked: {}", partitions);
                    disruptorManager.drainAll();
                }
                log.info("Drain completed before rebalance");
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                if (!partitions.isEmpty()) {
                    log.info("Partitions assigned: {}", partitions);
                }
            }
        });
        return factory;
    }

    /**
     * EventLog 复制专用 ContainerFactory
     * 始终自动启动，不受 KafkaConsumerStartupService 的 stop/start 控制
     * 主实例和从实例都需要持续消费 MATCHING_EVENTLOG_SYNC，通过 haService.isPrimary() 过滤消息
     */
    @Bean("replicationContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> replicationContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        // autoStartup=true 且不加入全局 registry 的 stop/start 管理
        factory.setAutoStartup(true);
        return factory;
    }
}