package com.matching.monitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 测试 KafkaListenerMonitor 的监控逻辑，不再自动重启
 */
class KafkaListenerMonitorTest {

    private KafkaListenerEndpointRegistry registry;
    private KafkaListenerMonitor monitor;

    @BeforeEach
    void setUp() {
        registry = mock(KafkaListenerEndpointRegistry.class);
        monitor = new KafkaListenerMonitor();
        // 使用反射设置registry
        org.springframework.test.util.ReflectionTestUtils.setField(monitor, "kafkaListenerEndpointRegistry", registry);
    }

    @Test
    @DisplayName("checkListenerStatus 不再自动重启Kafka消费者")
    void testCheckListenerStatusDoesNotAutoRestart() {
        // 设置shouldBeRunning=true，但isRunning=false
        monitor.setShouldBeRunning(true);
        when(registry.isRunning()).thenReturn(false);

        // 调用检查方法
        monitor.checkListenerStatus();

        // 验证没有调用start()方法
        verify(registry, never()).start();
    }

    @Test
    @DisplayName("checkListenerStatus 记录连续失败次数")
    void testCheckListenerStatusRecordsConsecutiveFailures() {
        monitor.setShouldBeRunning(true);
        when(registry.isRunning()).thenReturn(false);

        // 第一次失败
        monitor.checkListenerStatus();
        KafkaListenerMonitor.KafkaListenerStatus status1 = monitor.getStatus();
        assertEquals(1, status1.getConsecutiveFailures());

        // 第二次失败
        monitor.checkListenerStatus();
        KafkaListenerMonitor.KafkaListenerStatus status2 = monitor.getStatus();
        assertEquals(2, status2.getConsecutiveFailures());
    }

    @Test
    @DisplayName("checkListenerStatus 状态正常时重置失败计数")
    void testCheckListenerStatusResetsFailuresOnSuccess() {
        monitor.setShouldBeRunning(true);
        when(registry.isRunning()).thenReturn(false);

        // 先失败几次
        monitor.checkListenerStatus();
        monitor.checkListenerStatus();
        assertEquals(2, monitor.getStatus().getConsecutiveFailures());

        // 然后成功
        when(registry.isRunning()).thenReturn(true);
        monitor.checkListenerStatus();

        // 失败计数应重置
        assertEquals(0, monitor.getStatus().getConsecutiveFailures());
    }

    @Test
    @DisplayName("getStatus 返回正确的状态信息")
    void testGetStatusReturnsCorrectInfo() {
        monitor.setShouldBeRunning(true);
        when(registry.isRunning()).thenReturn(false);

        KafkaListenerMonitor.KafkaListenerStatus status = monitor.getStatus();

        assertTrue(status.isShouldBeRunning());
        assertFalse(status.isRunning());
        assertFalse(status.isHealthy());
    }
}