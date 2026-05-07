package com.matching.config;

import com.matching.disruptor.DisruptorManager;
import com.matching.ha.InstanceLeaderElection;
import com.matching.service.EventLogReplicationSender;
import com.matching.service.HAService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GracefulShutdown implements ApplicationListener<ContextClosedEvent> {

    private final KafkaListenerEndpointRegistry kafkaRegistry;
    private final DisruptorManager disruptorManager;
    private final InstanceLeaderElection leaderElection;
    private final HAService haService;
    private final EventLogReplicationSender eventLogReplicationSender;

    public GracefulShutdown(KafkaListenerEndpointRegistry kafkaRegistry,
                           DisruptorManager disruptorManager,
                           InstanceLeaderElection leaderElection,
                           HAService haService,
                           EventLogReplicationSender eventLogReplicationSender) {
        this.kafkaRegistry = kafkaRegistry;
        this.disruptorManager = disruptorManager;
        this.leaderElection = leaderElection;
        this.haService = haService;
        this.eventLogReplicationSender = eventLogReplicationSender;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("=== Graceful shutdown started ===");

        // Step 1: Pause all Kafka consumers - stop polling new messages but keep partition assignment
        // This prevents new events from entering Disruptor while we drain
        log.info("Step 1: Pausing Kafka consumers...");
        for (MessageListenerContainer container : kafkaRegistry.getListenerContainers()) {
            container.pause();
        }
        log.info("Step 1: Kafka consumers paused");

        // Step 2: Drain all Disruptors - process remaining events, flush WAL+Redis
        // Partitions are still held by this instance, new instance cannot start consuming
        log.info("Step 2: Draining Disruptors (flush WAL+Redis)...");
        disruptorManager.shutdown();
        log.info("Step 2: Disruptors drained and stopped");

        // Step 3: Stop EventLog replication sender (no more events to replicate)
        log.info("Step 3: Stopping EventLog replication sender...");
        try {
            eventLogReplicationSender.stopSending();
        } catch (Exception e) {
            log.warn("Step 3: Failed to stop EventLog replication sender: {}", e.getMessage());
        }
        log.info("Step 3: EventLog replication sender stopped");

        // Step 4: Stop Kafka consumers - release partitions, trigger rebalance
        // All data is now persisted, safe for new instance to take over.
        log.info("Step 4: Stopping Kafka consumers (triggering rebalance)...");
        kafkaRegistry.stop();
        log.info("Step 4: Kafka consumers stopped");

        // Step 5: Mark as STANDBY
        if (leaderElection.isHaEnabled()) {
            leaderElection.startDeactivate();
            leaderElection.completeDeactivate();
            log.info("Step 5: Marked as STANDBY");
        }

        // Step 6: 清除 Redis PRIMARY 标志，防止重启后误自动激活
        // 计划内停机（systemd stop）不应触发 OOM 自动恢复逻辑
        try {
            haService.persistRoleOnShutdown();
            log.info("Step 6: Cleared PRIMARY role flag in Redis");
        } catch (Exception e) {
            log.warn("Step 6: Failed to clear PRIMARY role flag: {}", e.getMessage());
        }

        log.info("=== Graceful shutdown completed ===");
    }
}