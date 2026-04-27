package com.matching.service;

import com.matching.config.symbol.SymbolConfigService;
import com.matching.ha.InstanceLeaderElection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 HAService 状态机：
 * STANDBY → ACTIVATING → PRIMARY
 * PRIMARY → DEACTIVATING → STANDBY
 * 失败回滚、并发保护、中间状态语义
 */
class HAStateMachineTest {

    private final Map<String, String> redisStore = new ConcurrentHashMap<>();
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doAnswer(inv -> { redisStore.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(valueOps).set(anyString(), anyString());
        when(valueOps.get(anyString())).thenAnswer(inv -> redisStore.get(inv.getArgument(0)));
        doAnswer(inv -> { redisStore.remove(inv.getArgument(0)); return true; })
                .when(redisTemplate).delete(anyString());
    }

    // ==================== 正常状态转换 ====================

    @Test
    @DisplayName("STANDBY → activate → PRIMARY 状态转换正确")
    void testActivateTransition() {
        HAService ha = createHAService("node-1", true, true);

        assertEquals("STANDBY", ha.getRole());
        assertFalse(ha.isActive());

        boolean result = ha.activate();

        assertTrue(result);
        assertEquals("PRIMARY", ha.getRole());
        assertTrue(ha.isActive());
        assertTrue(ha.isPrimary());
        assertEquals("ACTIVE", ha.getStatus().getStatus());
        // Redis PRIMARY 标志已写入
        assertEquals("PRIMARY", redisStore.get("matching:ha:role:node-1"));
    }

    @Test
    @DisplayName("PRIMARY → deactivate → STANDBY 状态转换正确")
    void testDeactivateTransition() {
        HAService ha = createHAService("node-1", true, true);
        ha.activate();
        assertEquals("PRIMARY", ha.getRole());

        boolean result = ha.deactivate();

        assertTrue(result);
        assertEquals("STANDBY", ha.getRole());
        assertFalse(ha.isActive());
        assertTrue(ha.isStandby());
        assertEquals("INACTIVE", ha.getStatus().getStatus());
        // Redis PRIMARY 标志已清除
        assertNull(redisStore.get("matching:ha:role:node-1"));
    }

    @Test
    @DisplayName("完整切换流程：STANDBY → PRIMARY → STANDBY")
    void testFullCycle() {
        HAService ha = createHAService("node-1", true, true);

        ha.activate();
        assertEquals("PRIMARY", ha.getRole());

        ha.deactivate();
        assertEquals("STANDBY", ha.getRole());

        // 可以再次 activate
        ha.activate();
        assertEquals("PRIMARY", ha.getRole());
    }

    // ==================== 中间状态语义 ====================

    @Test
    @DisplayName("ACTIVATING 中间态：isActive=false，不处理订单")
    void testActivatingStateIsNotActive() {
        // 模拟 Kafka 启动慢，activate 过程中检查状态
        HAService ha = createHAServiceWithSlowKafka("node-1");

        // activate 在另一个线程执行
        AtomicInteger rolesDuringActivation = new AtomicInteger(0);
        Thread activateThread = new Thread(() -> ha.activate());
        activateThread.start();

        // 短暂等待进入 ACTIVATING 状态
        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // ACTIVATING 期间 isActive 应为 false
        if ("ACTIVATING".equals(ha.getRole())) {
            assertFalse(ha.isActive(), "ACTIVATING 中间态不应处理订单");
            assertEquals("ACTIVATING", ha.getStatus().getStatus());
        }

        try { activateThread.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Test
    @DisplayName("DEACTIVATING 中间态：isActive=false，不处理订单")
    void testDeactivatingStateIsNotActive() {
        HAService ha = createHAService("node-1", true, true);
        ha.activate();

        // 手动设置为 DEACTIVATING
        ha.forceSetStatus("DEACTIVATING", false, "DEACTIVATING");

        assertFalse(ha.isActive(), "DEACTIVATING 中间态不应处理订单");
        assertTrue(ha.isTransitioning());
        assertEquals("DEACTIVATING", ha.getStatus().getStatus());
    }

    // ==================== 失败回滚 ====================

    @Test
    @DisplayName("activate 失败（Kafka 启动失败）→ 回滚到 STANDBY")
    void testActivateRollbackOnKafkaFailure() {
        HAService ha = createHAService("node-1", true, false); // Kafka 启动失败

        boolean result = ha.activate();

        assertFalse(result);
        assertEquals("STANDBY", ha.getRole());
        assertFalse(ha.isActive());
        // Redis PRIMARY 标志不应写入
        assertNull(redisStore.get("matching:ha:role:node-1"));
    }

    @Test
    @DisplayName("deactivate 失败（Snapshot 异常）→ 回滚到 PRIMARY")
    void testDeactivateRollbackOnSnapshotFailure() {
        HAService ha = createHAServiceWithSnapshotFailure("node-1");
        ha.forceSetStatus("PRIMARY", true, "ACTIVE");

        boolean result = ha.deactivate();

        assertFalse(result);
        assertEquals("PRIMARY", ha.getRole());
        assertTrue(ha.isActive());
    }

    // ==================== 非法状态转换 ====================

    @Test
    @DisplayName("STANDBY 不能 deactivate")
    void testCannotDeactivateFromStandby() {
        HAService ha = createHAService("node-1", true, true);
        assertEquals("STANDBY", ha.getRole());

        boolean result = ha.deactivate();

        assertFalse(result);
        assertEquals("STANDBY", ha.getRole()); // 状态不变
    }

    @Test
    @DisplayName("PRIMARY 不能重复 activate")
    void testCannotActivateFromPrimary() {
        HAService ha = createHAService("node-1", true, true);
        ha.activate();
        assertEquals("PRIMARY", ha.getRole());

        boolean result = ha.activate(); // 重复 activate

        assertFalse(result);
        assertEquals("PRIMARY", ha.getRole()); // 状态不变
    }

    @Test
    @DisplayName("ACTIVATING 中间态不能再次 activate")
    void testCannotActivateFromActivating() {
        HAService ha = createHAService("node-1", true, true);
        ha.forceSetStatus("ACTIVATING", false, "ACTIVATING");

        boolean result = ha.activate();

        assertFalse(result);
        assertEquals("ACTIVATING", ha.getRole()); // 状态不变
    }

    @Test
    @DisplayName("DEACTIVATING 中间态不能 activate")
    void testCannotActivateFromDeactivating() {
        HAService ha = createHAService("node-1", true, true);
        ha.forceSetStatus("DEACTIVATING", false, "DEACTIVATING");

        boolean result = ha.activate();

        assertFalse(result);
        assertEquals("DEACTIVATING", ha.getRole()); // 状态不变
    }

    // ==================== 并发保护 ====================

    @Test
    @DisplayName("并发 activate：只有一个成功，其余失败")
    void testConcurrentActivateOnlyOneSucceeds() throws InterruptedException {
        HAService ha = createHAService("node-1", true, true);

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    if (ha.activate()) successCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(1, successCount.get(), "并发 activate 只有一个应该成功");
        assertEquals("PRIMARY", ha.getRole());
    }

    // ==================== emergencyDeactivate ====================

    @Test
    @DisplayName("emergencyDeactivate 强制切到 STANDBY，不清除 Redis PRIMARY 标志（保留自动恢复能力）")
    void testEmergencyDeactivateKeepsPrimaryFlag() {
        HAService ha = createHAService("node-1", true, true);
        ha.activate();
        assertEquals("PRIMARY", ha.getRole());
        assertEquals("PRIMARY", redisStore.get("matching:ha:role:node-1"));

        ha.emergencyDeactivate("OOM detected");

        assertEquals("STANDBY", ha.getRole());
        assertFalse(ha.isActive());
        // 紧急降级保留 PRIMARY 标志，重启后可自动恢复
        assertEquals("PRIMARY", redisStore.get("matching:ha:role:node-1"));
    }

    @Test
    @DisplayName("计划内 deactivate 清除 Redis PRIMARY 标志（防止重启后误激活）")
    void testNormalDeactivateClearsPrimaryFlag() {
        HAService ha = createHAService("node-1", true, true);
        ha.activate();
        assertEquals("PRIMARY", redisStore.get("matching:ha:role:node-1"));

        ha.deactivate();

        assertNull(redisStore.get("matching:ha:role:node-1"),
                "计划内 deactivate 应清除 PRIMARY 标志");
    }

    // ==================== Helpers ====================

    private HAService createHAService(String instanceId, boolean haEnabled, boolean kafkaSuccess) {
        ChronicleQueueEventLog cqEventLog = mock(ChronicleQueueEventLog.class);
        FastRecoveryService recoveryService = mock(FastRecoveryService.class);
        SymbolConfigService symbolConfigService = mock(SymbolConfigService.class);
        KafkaConsumerStartupService kafkaService = mock(KafkaConsumerStartupService.class);
        SnapshotService snapshotService = mock(SnapshotService.class);
        OrderBookService orderBookService = mock(OrderBookService.class);
        InstanceLeaderElection leaderElection = mock(InstanceLeaderElection.class);
        EventLogReplicationSender eventLogReplicationSender = mock(EventLogReplicationSender.class);

        when(symbolConfigService.getActiveSymbolIds()).thenReturn(Collections.emptyList());
        when(kafkaService.startConsumers()).thenReturn(kafkaSuccess);
        when(kafkaService.stopConsumers()).thenReturn(true);
        when(orderBookService.getAllEngines()).thenReturn(Collections.emptyMap());

        HAService ha = new HAService(redisTemplate, cqEventLog, recoveryService, symbolConfigService,
                kafkaService, snapshotService, orderBookService, leaderElection, eventLogReplicationSender);
        setField(ha, "instanceId", instanceId);
        setField(ha, "haEnabled", haEnabled);
        setField(ha, "autoFailoverEnabled", false);
        return ha;
    }

    private HAService createHAServiceWithSlowKafka(String instanceId) {
        ChronicleQueueEventLog cqEventLog = mock(ChronicleQueueEventLog.class);
        FastRecoveryService recoveryService = mock(FastRecoveryService.class);
        SymbolConfigService symbolConfigService = mock(SymbolConfigService.class);
        KafkaConsumerStartupService kafkaService = mock(KafkaConsumerStartupService.class);
        SnapshotService snapshotService = mock(SnapshotService.class);
        OrderBookService orderBookService = mock(OrderBookService.class);
        InstanceLeaderElection leaderElection = mock(InstanceLeaderElection.class);
        EventLogReplicationSender eventLogReplicationSender = mock(EventLogReplicationSender.class);

        when(symbolConfigService.getActiveSymbolIds()).thenReturn(Collections.emptyList());
        when(kafkaService.startConsumers()).thenAnswer(inv -> {
            Thread.sleep(200); // 模拟慢启动
            return true;
        });
        when(kafkaService.stopConsumers()).thenReturn(true);
        when(orderBookService.getAllEngines()).thenReturn(Collections.emptyMap());

        HAService ha = new HAService(redisTemplate, cqEventLog, recoveryService, symbolConfigService,
                kafkaService, snapshotService, orderBookService, leaderElection, eventLogReplicationSender);
        setField(ha, "instanceId", instanceId);
        setField(ha, "haEnabled", true);
        setField(ha, "autoFailoverEnabled", false);
        return ha;
    }

    private HAService createHAServiceWithSnapshotFailure(String instanceId) {
        ChronicleQueueEventLog cqEventLog = mock(ChronicleQueueEventLog.class);
        FastRecoveryService recoveryService = mock(FastRecoveryService.class);
        SymbolConfigService symbolConfigService = mock(SymbolConfigService.class);
        KafkaConsumerStartupService kafkaService = mock(KafkaConsumerStartupService.class);
        SnapshotService snapshotService = mock(SnapshotService.class);
        OrderBookService orderBookService = mock(OrderBookService.class);
        InstanceLeaderElection leaderElection = mock(InstanceLeaderElection.class);
        EventLogReplicationSender eventLogReplicationSender = mock(EventLogReplicationSender.class);

        when(symbolConfigService.getActiveSymbolIds()).thenReturn(Collections.emptyList());
        when(kafkaService.startConsumers()).thenReturn(true);
        when(kafkaService.stopConsumers()).thenReturn(true);
        // Snapshot 失败
        when(orderBookService.getAllEngines()).thenThrow(new RuntimeException("Snapshot failure"));

        HAService ha = new HAService(redisTemplate, cqEventLog, recoveryService, symbolConfigService,
                kafkaService, snapshotService, orderBookService, leaderElection, eventLogReplicationSender);
        setField(ha, "instanceId", instanceId);
        setField(ha, "haEnabled", true);
        setField(ha, "autoFailoverEnabled", false);
        return ha;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            fail("setField failed: " + fieldName + " - " + e.getMessage());
        }
    }
}
