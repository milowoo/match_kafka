package com.matching.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Kafka超时配置管理 统一管理所有Kafka相关的超时设置，防止网络延迟时误判失败
 */
@Component
@ConfigurationProperties(prefix = "matching.kafka.timeout")
@Data
public class KafkaTimeoutConfig {

    /**
     * 生产者请求超时时间（毫秒） 默认30秒，适应网络延迟场景
     */
    private int requestTimeoutMs = 30000;

    /**
     * 生产者总交付超时时间（毫秒） 默认2分钟，包含重试时间
     */
    private int deliveryTimeoutMs = 120000;

    /**
     * 重试间隔时间（毫秒） 默认1秒
     */
    private int retryBackoffMs = 1000;

    /**
     * 同步发送超时时间（毫秒） 用于get()方法的超时设置
     */
    private int syncSendTimeoutMs = 10000;

    /**
     * 批量发送超时时间（毫秒） 用于批量操作的超时设置
     */
    private int batchSendTimeoutMs = 15000;

    /**
     * 消费者会话超时时间（毫秒）
     */
    private int consumerSessionTimeoutMs = 30000;

    /**
     * 消费者心跳间隔时间（毫秒）
     */
    private int consumerHeartbeatIntervalMs = 3000;
}