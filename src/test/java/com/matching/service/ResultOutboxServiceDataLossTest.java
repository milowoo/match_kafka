package com.matching.service;

import com.matching.config.KafkaTimeoutConfig;
import com.matching.ha.InstanceLeaderElection;
import com.matching.service.outbox.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResultOutboxServiceDataLossTest {

    @Mock private KafkaTemplate<String, byte[]> kafkaTemplate;
    @Mock private InstanceLeaderElection leaderElection;
    @Mock private RetryQueueManager retryQueueManager;
    @Mock private AlertService alertService;
    @Mock private KafkaTimeoutConfig kafkaTimeoutConfig;
    @Mock private KafkaTimeoutMonitorService kafkaTimeoutMonitorService;

    private ResultOutboxService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ResultOutboxService(kafkaTemplate, leaderElection, retryQueueManager, alertService,
                kafkaTimeoutConfig, kafkaTimeoutMonitorService);
        ReflectionTestUtils.setField(service, "matchResultTopic", "test-topic");
        service.init();
        when(leaderElection.isActive()).thenReturn(true);
    }

    @Test
    void testDataLossPreventionWhenEntryQueueFull() throws Exception {
        CompletableFuture<org.springframework.kafka.support.SendResult<String, byte[]>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka send failed"));
        when(kafkaTemplate.send(anyString(), anyString(), any(byte[].class))).thenReturn(failedFuture);

        List<OutboxEntry> entries = Arrays.asList(
                new OutboxEntry("key1", "data1".getBytes()),
                new OutboxEntry("key2", "data2".getBytes())
        );

        service.sendBatch(entries, null);

        Thread.sleep(200); // 等待异步回调
        verify(retryQueueManager, atLeastOnce()).addToRetryQueue(any(OutboxEntry.class));
    }

    @Test
    void testEmergencyQueueProcessing() {
        ConcurrentLinkedQueue<OutboxEntry> mockRetryQueue = new ConcurrentLinkedQueue<>();
        when(retryQueueManager.getRetryQueue()).thenReturn(mockRetryQueue);
        when(retryQueueManager.getPersistentFailureQueue())
                .thenReturn(new java.util.concurrent.LinkedBlockingQueue<>());
        when(kafkaTimeoutConfig.getSyncSendTimeoutMs()).thenReturn(5000);

        service.retryPending();

        verify(retryQueueManager, atLeastOnce()).processPersistentFailureQueue(anyInt());
        verify(retryQueueManager, atLeastOnce()).getRetryQueue();
    }

    @Test
    void testServiceInitialization() {
        assertNotNull(service);
        verify(retryQueueManager, never()).addToRetryQueue(any());
        verify(alertService, never()).sendAlert(anyString(), anyString());
    }

    @Test
    void testBatchSending() throws Exception {
        CompletableFuture<org.springframework.kafka.support.SendResult<String, byte[]>> successFuture =
                CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), any(byte[].class))).thenReturn(successFuture);

        List<OutboxEntry> entries = Arrays.asList(
                new OutboxEntry("key1", "data1".getBytes()),
                new OutboxEntry("key2", "data2".getBytes())
        );

        service.sendBatch(entries, null);

        Thread.sleep(200);
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), any(byte[].class));
    }
}
