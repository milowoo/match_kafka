package com.matching.service;

import com.matching.config.symbol.SymbolConfigService;
import com.matching.ha.InstanceLeaderElection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 OOM 重启后主实例自动恢复 PRIMARY 标志
 */
class HAAutoRecoverTest {

    // 模拟 Redis 存储
    private final Map<String, String> redisStore = new ConcurrentHashMap<>();
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // 模拟 Redis get/set/delete
        doAnswer(inv -> { redisStore.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(valueOps).set(anyString(), anyString());
        when(valueOps.get(anyString())).thenAnswer(inv -> redisStore.get(inv.getArgument(0)));
        doAnswer(inv -> { redisStore.remove(inv.getArgument(0)); return true; })
                .when(redisTemplate).delete(anyString());
    }

    @Test
    @DisplayName("正常 activate 后 Redis 持久化 PRIMARY 标志")
    void testActivatePersistsPrimaryRole() {
        HAService ha = createHAService("node-1");
        ha.forceSetStatus("PRIMARY", true, "ACTIVE");

        // 手动调用 persistRole
        invokePersistRole(ha, "PRIMARY");

        assertEquals("PRIMARY", redisStore.get("matching:ha:role:node-1"));
    }

    @Test
    @DisplayName("deactivate 后 Redis 清除 PRIMARY 标志")
    void testDeactivateClearsPrimaryRole() {
        HAService ha = createHAService("node-1");
        redisStore.put("matching:ha:role:node-1", "PRIMARY");

        invokePersistRole(ha, "STANDBY");

        assertNull(redisStore.get("matching:ha:role:node-1"));
    }

    @Test
    @DisplayName("OOM 重启后：Redis 有 PRIMARY 标志 → 自动 activate")
    void testAutoRecoverWhenWasPrimary() {
        // 模拟 OOM 前已持久化 PRIMARY
        redisStore.put("matching:ha:role:node-1", "PRIMARY");

        // 模拟重启：创建新的 HAService 实例（内存状态全部重置）
        HAService ha = createHAService("node-1");

        // 初始状态是 STANDBY
        assertEquals("STANDBY", ha.getRole());
        assertFalse(ha.isActive());

        // 调用 autoRecoverIfWasPrimary（模拟 @PostConstruct init 里的逻辑）
        invokeAutoRecover(ha);

        // 验证自动恢复为 PRIMARY
        assertTrue(ha.isActive());
        assertEquals("PRIMARY", ha.getRole());
        assertEquals("ACTIVE", ha.getStatus().getStatus());
    }

    @Test
    @DisplayName("OOM 重启后：Redis 无 PRIMARY 标志 → 保持 STANDBY")
    void testNoAutoRecoverWhenWasStandby() {
        // Redis 里没有 PRIMARY 标志（正常 STANDBY 实例）
        redisStore.remove("matching:ha:role:node-2");

        HAService ha = createHAService("node-2");
        invokeAutoRecover(ha);

        // 保持 STANDBY，不自动激活
        assertFalse(ha.isActive());
        assertEquals("STANDBY", ha.getRole());
    }

    @Test
    @DisplayName("OOM 重启后：Redis 有 PRIMARY 标志但 activate 失败 → 保持 STANDBY")
    void testAutoRecoverFailsGracefully() {
        redisStore.put("matching:ha:role:node-1", "PRIMARY");

        // 创建一个 activate 会失败的 HAService（symbolConfigService 返回空列表）
        HAService ha = createHAServiceWithEmptySymbols("node-1");
        invokeAutoRecover(ha);

        // activate 失败后应保持 STANDBY，不崩溃
        assertFalse(ha.isActive());
        assertEquals("STANDBY", ha.getRole());
    }

    @Test
    @DisplayName("两个实例：node-1 是 PRIMARY，node-2 是 STANDBY，各自重启后角色正确")
    void testTwoInstancesAutoRecover() {
        // node-1 OOM 前是 PRIMARY
        redisStore.put("matching:ha:role:node-1", "PRIMARY");
        // node-2 OOM 前是 STANDBY（无标志）

        HAService ha1 = createHAService("node-1");
        HAService ha2 = createHAService("node-2");

        invokeAutoRecover(ha1);
        invokeAutoRecover(ha2);

        assertTrue(ha1.isActive(), "node-1 应自动恢复为 PRIMARY");
        assertEquals("PRIMARY", ha1.getRole());

        assertFalse(ha2.isActive(), "node-2 应保持 STANDBY");
        assertEquals("STANDBY", ha2.getRole());
    }

    @Test
    @DisplayName("Redis 不可用时 autoRecover 不抛异常，保持 STANDBY")
    void testAutoRecoverRedisUnavailable() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis connection refused"));

        HAService ha = createHAService("node-1");

        // 不应抛异常
        assertDoesNotThrow(() -> invokeAutoRecover(ha));
        assertFalse(ha.isActive());
        assertEquals("STANDBY", ha.getRole());
    }

    // ==================== Helpers ====================

    private HAService createHAService(String instanceId) {
        ChronicleQueueEventLog cqEventLog = mock(ChronicleQueueEventLog.class);
        FastRecoveryService recoveryService = mock(FastRecoveryService.class);
        SymbolConfigService symbolConfigService = mock(SymbolConfigService.class);
        KafkaConsumerStartupService kafkaConsumerStartupService = mock(KafkaConsumerStartupService.class);
        SnapshotService snapshotService = mock(SnapshotService.class);
        OrderBookService orderBookService = mock(OrderBookService.class);
        InstanceLeaderElection leaderElection = mock(InstanceLeaderElection.class);
        EventLogReplicationSender eventLogReplicationSender = mock(EventLogReplicationSender.class);

        // 默认 activate 成功
        when(symbolConfigService.getActiveSymbolIds()).thenReturn(java.util.Collections.emptyList());
        when(kafkaConsumerStartupService.startConsumers()).thenReturn(true);

        HAService ha = new HAService(redisTemplate, cqEventLog, recoveryService, symbolConfigService,
                kafkaConsumerStartupService, snapshotService, orderBookService, leaderElection, eventLogReplicationSender);
        setField(ha, "instanceId", instanceId);
        setField(ha, "haEnabled", true);
        setField(ha, "autoFailoverEnabled", false);
        return ha;
    }

    private HAService createHAServiceWithEmptySymbols(String instanceId) {
        ChronicleQueueEventLog cqEventLog = mock(ChronicleQueueEventLog.class);
        FastRecoveryService recoveryService = mock(FastRecoveryService.class);
        SymbolConfigService symbolConfigService = mock(SymbolConfigService.class);
        KafkaConsumerStartupService kafkaConsumerStartupService = mock(KafkaConsumerStartupService.class);
        SnapshotService snapshotService = mock(SnapshotService.class);
        OrderBookService orderBookService = mock(OrderBookService.class);
        InstanceLeaderElection leaderElection = mock(InstanceLeaderElection.class);
        EventLogReplicationSender eventLogReplicationSender = mock(EventLogReplicationSender.class);

        // Kafka 启动失败，导致 activate 失败
        when(symbolConfigService.getActiveSymbolIds()).thenReturn(java.util.Collections.emptyList());
        when(kafkaConsumerStartupService.startConsumers()).thenReturn(false);

        HAService ha = new HAService(redisTemplate, cqEventLog, recoveryService, symbolConfigService,
                kafkaConsumerStartupService, snapshotService, orderBookService, leaderElection, eventLogReplicationSender);
        setField(ha, "instanceId", instanceId);
        setField(ha, "haEnabled", true);
        setField(ha, "autoFailoverEnabled", false);
        return ha;
    }

    private void invokeAutoRecover(HAService ha) {
        try {
            Method m = HAService.class.getDeclaredMethod("autoRecoverIfWasPrimary");
            m.setAccessible(true);
            m.invoke(ha);
        } catch (Exception e) {
            fail("invokeAutoRecover failed: " + e.getMessage());
        }
    }

    private void invokePersistRole(HAService ha, String role) {
        try {
            Method m = HAService.class.getDeclaredMethod("persistRole", String.class);
            m.setAccessible(true);
            m.invoke(ha, role);
        } catch (Exception e) {
            fail("invokePersistRole failed: " + e.getMessage());
        }
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
