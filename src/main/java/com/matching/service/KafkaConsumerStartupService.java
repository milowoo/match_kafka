package com.matching.service;

import com.matching.monitor.KafkaListenerMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

    /**
     * Kafka 消费者启动管理服务
     * 职责：
     * 1. 管理消费者启动/停止的状态标志（shouldBeRunning）
     * 2. 启动/停止消费者并验证状态
     * 3. 提供健康检查方法
     *
     * 注意：KafkaListenerMonitor 只负责告警和计数，不负责启动消费者
     */
@Slf4j
@Service
public class KafkaConsumerStartupService {

    private final KafkaListenerEndpointRegistry kafkaListenerRegistry;
    private final KafkaListenerMonitor kafkaListenerMonitor;

    /**
     * 消费者应该运行的状态标志
     * true: 消费者应该处于运行状态
     * false: 消费者应该停止（如紧急降级、系统关闭时）
     */
    private final AtomicBoolean shouldBeRunning = new AtomicBoolean(false);

    /**
     * 启动验证超时（毫秒）
     * 验证消费者是否成功启动时的最大等待时间
     * 配置项：matching.kafka.startup-verify-timeout-ms，默认值：2000ms
     */
    @Value("${matching.kafka.startup-verify-timeout-ms:2000}")
    private long startupVerifyTimeoutMs;

    /**
     * 停止验证超时（毫秒）
     * 验证消费者是否成功停止时的最大等待时间
     * 配置项：matching.kafka.stop-verify-timeout-ms，默认值：1000ms
     */
    @Value("${matching.kafka.stop-verify-timeout-ms:1000}")
    private long stopVerifyTimeoutMs;

    /**
     * 验证检查间隔（毫秒）
     * 在轮询检查消费者状态时的间隔时间
     * 配置项：matching.kafka.verify-check-interval-ms，默认值：500ms
     *
     * Bug修复：原来是硬编码100ms，导致轮询频率过高，增加CPU消耗
     */
    @Value("${matching.kafka.verify-check-interval-ms:500}")
    private long verifyCheckIntervalMs;

    public KafkaConsumerStartupService(KafkaListenerEndpointRegistry kafkaListenerRegistry,
                                       KafkaListenerMonitor kafkaListenerMonitor) {
        this.kafkaListenerRegistry = kafkaListenerRegistry;
        this.kafkaListenerMonitor = kafkaListenerMonitor;
    }

    /**
     * 启动消费者（由 activate 调用，或 KafkaOpsController 手动调用）
     * 流程：
     * 1. 设置 shouldBeRunning=true，允许 ReceiverMq 处理消息
     * 2. 启动 Spring Kafka 监听容器
     * 3. 验证启动是否成功（等待容器就绪）
     *
     * @return true: 启动成功且通过验证，false: 启动失败或验证超时
     */
    public boolean startConsumers() {
        try {
            log.info("[StartConsumers] Starting Kafka consumers...");

            // Step 1: 设置标志，允许 ReceiverMq 接受消息
            shouldBeRunning.set(true);
            log.info("[StartConsumers] Set shouldBeRunning=true, ReceiverMq will now accept messages");

            // Step 2: 启动监听容器
            Collection<MessageListenerContainer> containers = kafkaListenerRegistry.getListenerContainers();
            if (containers == null || containers.isEmpty()) {
                log.error("[StartConsumers] No Kafka listener containers found");
                shouldBeRunning.set(false);
                return false;
            }

            for (MessageListenerContainer container : containers) {
                if (!container.isRunning()) {
                    container.start();
                    log.info("[StartConsumers] Started Kafka listener container: {}", container.getGroupId());
                } else {
                    log.info("[StartConsumers] Kafka listener container already running: {}", container.getGroupId());
                }
            }

            // Step 3: 验证启动是否成功
            boolean verified = verifyStartup();
            if (!verified) {
                log.error("[StartConsumers] Failed to verify Kafka consumer startup");
                shouldBeRunning.set(false);
                return false;
            }

            log.info("[StartConsumers] Kafka consumers started and verified successfully");
            return true;
        } catch (Exception e) {
            log.error("[StartConsumers] Failed to start Kafka consumers", e);
            shouldBeRunning.set(false);
            return false;
        }

    }

    /**
     * 停止消费者（由 deactivate 或 emergencyDeactivate 调用，或 KafkaOpsController 手动调用）
     * 流程：
     * 1. 设置 shouldBeRunning=false，ReceiverMq 停止处理新消息（不 ack）
     * 2. 停止 Spring Kafka 监听容器
     * 3. 验证停止是否成功
     *
     * 注意：消息不 ack 意味着 Kafka 会重投该消息（当消费者重启后会重新处理）
     * 这样保证了消息不丢失
     *
     * @return true: 停止成功且通过验证，false: 停止失败或验证超时
     */
    public boolean stopConsumers() {
        try {
            log.info("[StopConsumers] Stopping Kafka consumers...");

            // Step 1: 设置标志，ReceiverMq 停止处理消息（不 ack，Kafka 会重投）
            shouldBeRunning.set(false);
            log.info("[StopConsumers] Set shouldBeRunning=false, ReceiverMq will now skip messages and not ack");

            // Step 2: 停止监听容器
            Collection<MessageListenerContainer> containers = kafkaListenerRegistry.getListenerContainers();
            if (containers != null && !containers.isEmpty()) {
                for (MessageListenerContainer container : containers) {
                    if (container.isRunning()) {
                        container.stop();
                        log.info("[StopConsumers] Stopped Kafka listener container: {}", container.getGroupId());
                    }
                }
            }

            // Step 3: 验证停止是否成功
            boolean verified = verifyStop();
            if (!verified) {
                log.error("[StopConsumers] Failed to verify Kafka consumer stop (timeout)");
                // 即使验证失败，也已经设置了 shouldBeRunning=false
                // 所以不会处理新消息，但可能有旧消息还在处理中
                return false;
            }

            log.info("[StopConsumers] Kafka consumers stopped and verified successfully");
            return true;
        } catch (Exception e) {
            log.error("[StopConsumers] Failed to stop Kafka consumers", e);
            return false;
        }
    }

    /**
     * 验证启动：等待消费者启动完成
     * 超时时间由 startupVerifyTimeoutMs 配置
     * 检查间隔由 verifyCheckIntervalMs 配置
     *
     * 成功条件：
     * - kafkaListenerRegistry.isRunning() 返回 true
     * - kafkaListenerMonitor.getStatus().isRunning() 返回 true
     * - 所有容器都处于运行状态
     *
     * @return true: 验证成功，false: 超时失败
     */
    private boolean verifyStartup() {
        log.info("[VerifyStartup] Starting verification with timeout={}ms, checkInterval={}ms",
                startupVerifyTimeoutMs, verifyCheckIntervalMs);

        long startTime = System.currentTimeMillis();
        long deadlineTime = startTime + startupVerifyTimeoutMs;

        while (System.currentTimeMillis() < deadlineTime) {
            try {
                // 检查条件 1: registry 是否运行
                if (!kafkaListenerRegistry.isRunning()) {
                    log.debug("[VerifyStartup] Registry not running yet, waiting...");
                    Thread.sleep(verifyCheckIntervalMs);
                    continue;
                }

                // 检查条件 2: monitor 状态
                KafkaListenerMonitor.KafkaListenerStatus monitorStatus = kafkaListenerMonitor.getStatus();
                if (!monitorStatus.isRunning()) {
                    log.debug("[VerifyStartup] Monitor status not ready, waiting...");
                    Thread.sleep(verifyCheckIntervalMs);
                    continue;
                }

                // 检查条件 3: 所有容器都运行
                Collection<MessageListenerContainer> containers = kafkaListenerRegistry.getListenerContainers();
                boolean allRunning = containers != null && containers.stream().allMatch(MessageListenerContainer::isRunning);
                if (!allRunning) {
                    log.debug("[VerifyStartup] Not all containers running, waiting...");
                    Thread.sleep(verifyCheckIntervalMs);
                    continue;
                }

                // 所有条件都满足，验证成功
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("[VerifyStartup] Verification succeeded in {}ms", elapsed);
                return true;

            } catch (InterruptedException e) {
                log.warn("[VerifyStartup] Verification interrupted", e);
                Thread.currentThread().interrupt();
                return false;
            }
        }

        // 超时
        long elapsed = System.currentTimeMillis() - startTime;
        log.warn("[VerifyStartup] Verification timeout after {}ms", elapsed);
        return false;
    }

    /**
     * 验证停止：等待消费者停止完成
     * 超时时间由 stopVerifyTimeoutMs 配置
     * 检查间隔由 verifyCheckIntervalMs 配置
     *
     * 成功条件：
     * - kafkaListenerRegistry.isRunning() 返回 false，或所有容器都停止了
     * - kafkaListenerMonitor 显示已停止
     *
     * @return true: 验证成功，false: 超时失败
     */
    private boolean verifyStop() {
        log.info("[VerifyStop] Starting verification with timeout={}ms, checkInterval={}ms",
                stopVerifyTimeoutMs, verifyCheckIntervalMs);

        long startTime = System.currentTimeMillis();
        long deadlineTime = startTime + stopVerifyTimeoutMs;

        while (System.currentTimeMillis() < deadlineTime) {
            try {
                // 检查条件 1: 是否还有容器在运行
                Collection<MessageListenerContainer> containers = kafkaListenerRegistry.getListenerContainers();
                boolean anyRunning = containers != null && containers.stream().anyMatch(MessageListenerContainer::isRunning);

                if (anyRunning) {
                    log.debug("[VerifyStop] Still have running containers, waiting...");
                    Thread.sleep(verifyCheckIntervalMs);
                    continue;
                }

                // 检查条件 2: registry 是否停止
                if (kafkaListenerRegistry.isRunning()) {
                    log.debug("[VerifyStop] Registry still running, waiting...");
                    Thread.sleep(verifyCheckIntervalMs);
                    continue;
                }

                // 所有条件都满足，验证成功
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("[VerifyStop] Verification succeeded in {}ms", elapsed);
                return true;

            } catch (InterruptedException e) {
                log.warn("[VerifyStop] Verification interrupted", e);
                Thread.currentThread().interrupt();
                return false;
            }
        }

        // 超时
        long elapsed = System.currentTimeMillis() - startTime;
        log.warn("[VerifyStop] Verification timeout after {}ms", elapsed);
        return false;
    }

    /**
     * 查询消费者应该运行的状态标志
     * 由 ReceiverMq 调用，用于决定是否处理消息
     *
     * 双重检查：ReceiverMq 还需要检查 haService.isActive()
     * 只有两个条件都为 true 才能处理消息
     *
     * @return true: 消费者应该运行，false: 消费者应该停止
     */
    public boolean shouldBeRunning() {
        return shouldBeRunning.get();
    }

    /**
     * 获取启动状态枚举
     *
     * @return 启动状态
     */
    public StartupStatus getStartupStatus() {
        if (!kafkaListenerRegistry.isRunning()) {
            return StartupStatus.STOPPED;
        }

        KafkaListenerMonitor.KafkaListenerStatus monitorStatus = kafkaListenerMonitor.getStatus();
        if (monitorStatus.isRunning()) {
            return StartupStatus.RUNNING;
        } else if (!monitorStatus.isHealthy()) {
            return StartupStatus.ERROR;
        } else {
            return StartupStatus.STARTING;
        }
    }

    /**
     * 启动状态枚举
     */
    public enum StartupStatus {
        STOPPED,    // 已停止
        STARTING,   // 正在启动
        RUNNING,    // 运行中
        ERROR       // 错误状态
    }

    /**
     * 生命周期钩子：服务关闭时停止消费者
     */
    @PreDestroy
    public void shutdown() {
        log.info("[Shutdown] KafkaConsumerStartupService shutting down");
        stopConsumers();
    }
}








