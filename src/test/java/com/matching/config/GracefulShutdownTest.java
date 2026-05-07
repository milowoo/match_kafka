package com.matching.config;

import com.matching.disruptor.DisruptorManager;
import com.matching.ha.InstanceLeaderElection;
import com.matching.service.EventLogReplicationSender;
import com.matching.service.HAService;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 GracefulShutdown 关闭顺序，特别是 Step 3 停止 EventLogReplicationSender
 */
class GracefulShutdownTest {

    @Test
    void testShutdownStopsEventLogReplicationSender() {
        KafkaListenerEndpointRegistry kafkaRegistry = mock(KafkaListenerEndpointRegistry.class);
        DisruptorManager disruptorManager = mock(DisruptorManager.class);
        InstanceLeaderElection leaderElection = mock(InstanceLeaderElection.class);
        HAService haService = mock(HAService.class);
        EventLogReplicationSender sender = mock(EventLogReplicationSender.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);

        when(kafkaRegistry.getListenerContainers()).thenReturn(Collections.singletonList(container));
        when(leaderElection.isHaEnabled()).thenReturn(true);

        GracefulShutdown shutdown = new GracefulShutdown(
                kafkaRegistry, disruptorManager, leaderElection, haService, sender);

        shutdown.onApplicationEvent(mock(ContextClosedEvent.class));

        // Step 3: 应停止 sender
        verify(sender).stopSending();
    }

    @Test
    void testShutdownOrder() {
        KafkaListenerEndpointRegistry kafkaRegistry = mock(KafkaListenerEndpointRegistry.class);
        DisruptorManager disruptorManager = mock(DisruptorManager.class);
        InstanceLeaderElection leaderElection = mock(InstanceLeaderElection.class);
        HAService haService = mock(HAService.class);
        EventLogReplicationSender sender = mock(EventLogReplicationSender.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);

        when(kafkaRegistry.getListenerContainers()).thenReturn(Collections.singletonList(container));
        when(leaderElection.isHaEnabled()).thenReturn(true);

        GracefulShutdown shutdown = new GracefulShutdown(
                kafkaRegistry, disruptorManager, leaderElection, haService, sender);

        shutdown.onApplicationEvent(mock(ContextClosedEvent.class));

        // 验证核心步骤均被调用
        verify(disruptorManager).shutdown();           // Step 2
        verify(sender).stopSending();                   // Step 3
        verify(kafkaRegistry).stop();                   // Step 4
        verify(leaderElection).startDeactivate();       // Step 5
        verify(leaderElection).completeDeactivate();    // Step 5
        verify(haService).persistRoleOnShutdown();      // Step 6
    }

    @Test
    void testShutdownWithoutHa() {
        KafkaListenerEndpointRegistry kafkaRegistry = mock(KafkaListenerEndpointRegistry.class);
        DisruptorManager disruptorManager = mock(DisruptorManager.class);
        InstanceLeaderElection leaderElection = mock(InstanceLeaderElection.class);
        HAService haService = mock(HAService.class);
        EventLogReplicationSender sender = mock(EventLogReplicationSender.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);

        when(kafkaRegistry.getListenerContainers()).thenReturn(Collections.singletonList(container));
        when(leaderElection.isHaEnabled()).thenReturn(false); // HA 禁用

        GracefulShutdown shutdown = new GracefulShutdown(
                kafkaRegistry, disruptorManager, leaderElection, haService, sender);

        shutdown.onApplicationEvent(mock(ContextClosedEvent.class));

        // 核心步骤仍然应执行
        verify(sender).stopSending();
        verify(kafkaRegistry).stop();

        // HA 禁用时不调用 leaderElection
        verify(leaderElection, never()).startDeactivate();
        verify(leaderElection, never()).completeDeactivate();
    }
}
