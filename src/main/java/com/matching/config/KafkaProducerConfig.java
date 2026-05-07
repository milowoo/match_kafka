package com.matching.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    private final KafkaTimeoutConfig timeoutConfig;

    public KafkaProducerConfig(KafkaTimeoutConfig timeoutConfig) {
        this.timeoutConfig = timeoutConfig;
    }

    private Map<String, Object> baseProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutConfig.getRequestTimeoutMs());
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, timeoutConfig.getDeliveryTimeoutMs());
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, timeoutConfig.getRetryBackoffMs());
        return props;
    }

    // ===================== Reliable: match result (acks=all, byte[]) =====================
    @Bean
    public ProducerFactory<String, byte[]> reliableProducerFactory() {
        Map<String, Object> props = baseProps();
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 65536);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean("reliableKafkaTemplate")
    @Primary
    public KafkaTemplate<String, byte[]> reliableKafkaTemplate() {
        return new KafkaTemplate<>(reliableProducerFactory());
    }

    // ===================== Fast: depth (acks=1, byte[]) =====================
    @Bean
    public ProducerFactory<String, byte[]> fastProducerFactory() {
        Map<String, Object> props = baseProps();
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean("fastKafkaTemplate")
    public KafkaTemplate<String, byte[]> fastKafkaTemplate() {
        return new KafkaTemplate<>(fastProducerFactory());
    }

    // ===================== DLQ: string (acks=1) =====================
    @Bean
    public ProducerFactory<String, String> dlqProducerFactory() {
        Map<String, Object> props = baseProps();
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean("dlqKafkaTemplate")
    public KafkaTemplate<String, String> dlqKafkaTemplate() {
        return new KafkaTemplate<>(dlqProducerFactory());
    }
}