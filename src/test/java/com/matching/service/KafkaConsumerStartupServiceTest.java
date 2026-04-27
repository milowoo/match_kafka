package com.matching.service;

import com.matching.monitor.KafkaListenerMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 测试 KafkaConsumerStartupService 的配置参数和验证逻辑
 */
class KafkaConsumerStartupServiceTest {

    private KafkaListenerEndpointRegistry registry;
    private KafkaListenerMonitor monitor;
    private KafkaConsumerStartupService service;

    @BeforeEach
    void setUp() {
        registry = mock(KafkaListenerEndpointRegistry.class);
        monitor = mock(KafkaListenerMonitor.class);
        service = new KafkaConsumerStartupService(registry, monitor);
    }

    @Test
    @DisplayName("verifyStartup 使用配置的超时时间")
    void testVerifyStartupUsesConfiguredTimeout() {
        // 设置配置参数
        ReflectionTestUtils.setField(service, "startupVerifyTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "verifyCheckIntervalMs", 200L);

        // Mock 状态：始终未就绪
        when(registry.isRunning()).thenReturn(false);
        when(monitor.getStatus()).thenReturn(new KafkaListenerMonitor.KafkaListenerStatus(false, false, 0));

        long startTime = System.currentTimeMillis();
        boolean result = ReflectionTestUtils.invokeMethod(service, "verifyStartup");
        long elapsed = System.currentTimeMillis() - startTime;

        assertFalse(result, "应该超时失败");
        assertTrue(elapsed >= 1000, "应该等待至少1000ms");
        assertTrue(elapsed < 1200, "不应该等待太久");
    }

    @Test
    @DisplayName("verifyStartup 使用配置的检查间隔")
    void testVerifyStartupUsesConfiguredCheckInterval() throws InterruptedException {
        ReflectionTestUtils.setField(service, "startupVerifyTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "verifyCheckIntervalMs", 100L);

        // Mock 状态：始终未就绪
        when(registry.isRunning()).thenReturn(false);
        when(monitor.getStatus()).thenReturn(new KafkaListenerMonitor.KafkaListenerStatus(false, false, 0));

        long startTime = System.currentTimeMillis();
        ReflectionTestUtils.invokeMethod(service, "verifyStartup");
        long elapsed = System.currentTimeMillis() - startTime;

        // 应该有多次检查（1000ms / 100ms = 10次）
        verify(registry, atLeast(9)).isRunning();
        verify(monitor, atLeast(9)).getStatus();
    }

    @Test
    @DisplayName("verifyStop 使用配置的超时时间")
    void testVerifyStopUsesConfiguredTimeout() {
        ReflectionTestUtils.setField(service, "stopVerifyTimeoutMs", 500L);

        // Mock 状态：停止后仍运行
        when(registry.isRunning()).thenReturn(true);
        when(monitor.getStatus()).thenReturn(new KafkaListenerMonitor.KafkaListenerStatus(false, true, 0));

        long startTime = System.currentTimeMillis();
        boolean result = ReflectionTestUtils.invokeMethod(service, "verifyStop");
        long elapsed = System.currentTimeMillis() - startTime;

        assertFalse(result, "应该失败");
        assertTrue(elapsed >= 500, "应该等待至少500ms");
    }

    @Test
    @DisplayName("verifyStartup 成功时立即返回")
    void testVerifyStartupReturnsImmediatelyOnSuccess() {
        ReflectionTestUtils.setField(service, "startupVerifyTimeoutMs", 5000L);
        ReflectionTestUtils.setField(service, "verifyCheckIntervalMs", 1000L);

        // Mock 状态：立即就绪
        when(registry.isRunning()).thenReturn(true);
        List<MessageListenerContainer> containers = Collections.singletonList(mock(MessageListenerContainer.class));
        when(registry.getListenerContainers()).thenReturn(containers);
        when(containers.get(0).isRunning()).thenReturn(true);
        when(monitor.getStatus()).thenReturn(new KafkaListenerMonitor.KafkaListenerStatus(true, true, 0));

        long startTime = System.currentTimeMillis();
        boolean result = ReflectionTestUtils.invokeMethod(service, "verifyStartup");
        long elapsed = System.currentTimeMillis() - startTime;

        assertTrue(result, "应该成功");
        assertTrue(elapsed < 100, "应该立即返回");
    }

    @Test
    @DisplayName("forceReset 方法不存在")
    void testForceResetMethodDoesNotExist() {
        // 验证方法不存在，避免误调用
        assertThrows(NoSuchMethodException.class, () -> {
            KafkaConsumerStartupService.class.getDeclaredMethod("forceReset");
        });
    }
}