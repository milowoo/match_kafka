package com.matching.service;

import com.matching.config.symbol.SymbolConfigService;
import com.matching.dto.HAStatus;
import com.matching.engine.MatchEngine;
import com.matching.ha.InstanceLeaderElection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HA 状态机：
 *
 *   STANDBY ──activate──→ ACTIVATING ──成功──→ PRIMARY
 *                              └──失败──→ STANDBY
 *
 *   PRIMARY ──deactivate──→ DEACTIVATING ──成功──→ STANDBY
 *                                └──失败──→ PRIMARY（回滚）
 *
 * 中间状态语义：
 *   ACTIVATING   - 正在恢复数据、启动 Kafka，不接受新订单，不对外服务
 *   DEACTIVATING - 正在停止消费、创建 Snapshot、切换 EventLog，不接受新订单
 */
@Service
public class HAService {
    private static final Logger log = LoggerFactory.getLogger(HAService.class);

    /**
     * HA 角色状态枚举
     */
    public enum Role {
        STANDBY,      // 从实例，不处理订单
        ACTIVATING,   // 中间态：正在激活为主实例
        PRIMARY,      // 主实例，正常处理订单
        DEACTIVATING  // 中间态：正在降级为从实例
    }

    @Value("${matching.ha.auto-failover:false}")
    private boolean autoFailoverEnabled;

    // Redis key：持久化实例角色，用于 OOM 重启后自动恢复 PRIMARY 状态
    private static final String ROLE_KEY_PREFIX = "matching:ha:role:";

    // 核心状态：使用枚举原子引用，保证状态转换的可见性
    private final AtomicReference<Role> role = new AtomicReference<>(Role.STANDBY);

    private final StringRedisTemplate redisTemplate;
    private final UnifiedChronicleQueueEventLog eventLog;
    private final FastRecoveryService recoveryService;
    private final SymbolConfigService symbolConfigService;
    private final KafkaConsumerStartupService kafkaConsumerStartupService;
    private final SnapshotService snapshotService;
    private final OrderBookService orderBookService;
    private final InstanceLeaderElection leaderElection;
    private final EventLogReplicationSender eventLogReplicationSender;

    @Value("${matching.ha.enabled:true}")
    private boolean haEnabled;

    @Value("${matching.ha.instance-id:node-1}")
    private String instanceId;

    @Value("${matching.ha.replication-ready-timeout-ms:30000}")
    private long replicationReadyTimeoutMs;


    public HAService(StringRedisTemplate redisTemplate,
                     UnifiedChronicleQueueEventLog eventLog,
                     FastRecoveryService recoveryService,
                     SymbolConfigService symbolConfigService,
                     KafkaConsumerStartupService kafkaConsumerStartupService,
                     SnapshotService snapshotService,
                     OrderBookService orderBookService,
                     InstanceLeaderElection leaderElection,
                     EventLogReplicationSender eventLogReplicationSender) {
        this.redisTemplate = redisTemplate;
        this.eventLog = eventLog;
        this.recoveryService = recoveryService;
        this.symbolConfigService = symbolConfigService;
        this.kafkaConsumerStartupService = kafkaConsumerStartupService;
        this.snapshotService = snapshotService;
        this.orderBookService = orderBookService;
        this.leaderElection = leaderElection;
        this.eventLogReplicationSender = eventLogReplicationSender;
    }

    @PostConstruct
    public void init() {
        if (haEnabled) {
            log.info("HA Service initialized - Instance: {}, Auto-failover: {}",
                    instanceId, autoFailoverEnabled);
            // OOM 重启后自动恢复：检查 Redis 里是否有持久化的 PRIMARY 标志
            autoRecoverIfWasPrimary();
        } else {
            // HA 禁用时，直接激活（单机模式）
            role.set(Role.PRIMARY);
            log.info("HA Service disabled - running in standalone mode, auto-activated");
        }
    }

    /**
     * OOM 重启后自动恢复：检查 Redis 里是否有持久化的 PRIMARY 标志
     */
    private void autoRecoverIfWasPrimary() {
        try {
            String persistedRole = redisTemplate.opsForValue().get(ROLE_KEY_PREFIX + instanceId);
            if ("PRIMARY".equals(persistedRole)) {
                log.info("[AutoRecover] Detected persisted PRIMARY role for instance {}, auto-activating...", instanceId);
                boolean activated = activate();
                if (activated) {
                    log.info("[AutoRecover] Instance {} auto-recovered as PRIMARY successfully", instanceId);
                } else {
                    log.error("[AutoRecover] Instance {} failed to auto-recover as PRIMARY, staying STANDBY", instanceId);
                }
            } else {
                log.info("[AutoRecover] No persisted PRIMARY role for instance {}, staying STANDBY", instanceId);
            }
        } catch (Exception e) {
            log.warn("[AutoRecover] Failed to check persisted role, staying STANDBY: {}", e.getMessage());
        }
    }

    // ==================== activate：STANDBY → ACTIVATING → PRIMARY ====================

    /**
     * 激活为主实例
     * STANDBY → ACTIVATING → PRIMARY
     * 失败时回滚到 STANDBY
     */
    public boolean activate() {
        if (!haEnabled) {
            log.warn("HA is disabled, cannot activate");
            return false;
        }

        // 状态检查：只有 STANDBY 才能 activate
        if (!role.compareAndSet(Role.STANDBY, Role.ACTIVATING)) {
            log.warn("[Activate] Cannot activate, current role: {}", role.get());
            return false;
        }

        log.info("[Activate] Instance {} STANDBY → ACTIVATING", instanceId);

        try {
            // Step 1: 恢复数据（Redis Snapshot + 本地 EventLog 回放）
            recoverFromBackup();

            // Step 2: 切换 EventLog 到主模式
            eventLog.switchAllToPrimary();

            // Step 3: 读取全局sent-seq，从上次发送的地方继续
            long lastSentSeq = readGlobalSentSeq();
            eventLogReplicationSender.setStartSeq(lastSentSeq + 1);

            // Step 4: 先启动 EventLog 复制发送服务（确保复制管道就绪）
            eventLogReplicationSender.startSending();

            // Step 5: 等待 Kafka 集群就绪（复制管道确认可用）
            boolean kafkaReady = eventLogReplicationSender.waitForKafkaReady(replicationReadyTimeoutMs);
            if (!kafkaReady) {
                log.error("[Activate] Kafka cluster not ready within {}ms, aborting activation", replicationReadyTimeoutMs);
                throw new RuntimeException("Kafka cluster not ready");
            }

            // Step 6: 等待重试队列积压清空（如果启动时有积压事件）
            if (!eventLogReplicationSender.isRetryQueueEmpty()) {
                log.info("[Activate] Waiting for replication retry queue to drain...");
                long waitDeadline = System.currentTimeMillis() + replicationReadyTimeoutMs;
                while (System.currentTimeMillis() < waitDeadline && !eventLogReplicationSender.isRetryQueueEmpty()) {
                    Thread.sleep(100);
                }
                if (!eventLogReplicationSender.isRetryQueueEmpty()) {
                    log.warn("[Activate] Replication retry queue not fully drained within timeout, continuing...");
                }
            }

            // Step 7: 复制管道就绪后，再启动 Kafka 消费（开始接受订单）
            boolean kafkaStarted = kafkaConsumerStartupService.startConsumers();
            if (!kafkaStarted) {
                throw new RuntimeException("Kafka consumer startup failed");
            }

            // Step 8: 切换到 PRIMARY
            role.set(Role.PRIMARY);
            leaderElection.activate();
            registerHeartbeat();
            persistRole("PRIMARY");

            log.info("[Activate] Instance {} ACTIVATING → PRIMARY successfully", instanceId);
            return true;

        } catch (Exception e) {
            log.error("[Activate] Failed to activate instance {}, rolling back to STANDBY", instanceId, e);
            // 回滚：停止已启动的 Kafka 消费和发送线程，切回 STANDBY
            try { kafkaConsumerStartupService.stopConsumers(); } catch (Exception ex) { /* ignore */ }
            try { eventLogReplicationSender.stopSending(); } catch (Exception ex) { /* ignore */ }
            try { eventLog.switchAllToStandby(); } catch (Exception ex) { /* ignore */ }
            role.set(Role.STANDBY);
            leaderElection.completeDeactivate();
            return false;
        }
    }

    // ==================== deactivate：PRIMARY → DEACTIVATING → STANDBY ====================

    /**
     * 降级为从实例
     * PRIMARY → DEACTIVATING → STANDBY
     * 失败时回滚到 PRIMARY
     */
    public boolean deactivate() {
        if (!haEnabled) {
            log.warn("HA is disabled, cannot deactivate");
            return false;
        }

        // 状态检查：只有 PRIMARY 才能 deactivate
        if (!role.compareAndSet(Role.PRIMARY, Role.DEACTIVATING)) {
            log.warn("[Deactivate] Cannot deactivate, current role: {}", role.get());
            return false;
        }

        log.info("[Deactivate] Instance {} PRIMARY → DEACTIVATING", instanceId);

        try {
            // Step 1: 停止 Kafka 消费（不再接收新订单）
            boolean kafkaStopped = kafkaConsumerStartupService.stopConsumers();
            if (!kafkaStopped) {
                log.warn("[Deactivate] Kafka consumers may not have stopped cleanly, continuing...");
            }

            // Step 2: 先停止后台发送线程，消除与 sendRemainingEvents 的并发竞态
            eventLogReplicationSender.stopSending();

            // Step 3: 检查EventLog发送完整性
            long committedSeq = eventLog.getMaxLocalSeq();
            long sentSeq = eventLogReplicationSender.getLastSentSeq();

            if (committedSeq > sentSeq) {
                log.info("[Deactivate] Found unsent events: committed={}, sent={}", committedSeq, sentSeq);

                // 发送剩余事件，确保从实例拥有完整数据
                // 此时发送线程已停，无并发问题
                eventLogReplicationSender.sendRemainingEvents(committedSeq);
                eventLogReplicationSender.waitForSendCompletion();
            }

            // Step 4: 创建最终 Snapshot（此时数据完整）
            createFinalSnapshot();

            // Step 5: 切换 EventLog 到从模式（不再写入新事件）
            eventLog.switchAllToStandby();

            // Step 6: 切换到 STANDBY
            role.set(Role.STANDBY);
            leaderElection.startDeactivate();
            leaderElection.completeDeactivate();
            unregisterHeartbeat();
            persistRole("STANDBY"); // 清除 Redis PRIMARY 标志，防止重启后误自动激活

            log.info("[Deactivate] Instance {} DEACTIVATING → STANDBY successfully", instanceId);
            return true;

        } catch (Exception e) {
            log.error("[Deactivate] Failed to deactivate instance {}, rolling back to PRIMARY", instanceId, e);
            // 回滚：重新启动 Kafka 消费，切回 PRIMARY
            try { kafkaConsumerStartupService.startConsumers(); } catch (Exception ex) { /* ignore */ }
            try { eventLog.switchAllToPrimary(); } catch (Exception ex) { /* ignore */ }
            role.set(Role.PRIMARY);
            return false;
        }
    }

    // ==================== emergencyDeactivate：任意状态 → STANDBY ====================

    /**
     * 紧急降级（OOM / 硬件故障时调用）
     * 不做回滚，强制切到 STANDBY
     */
    public boolean emergencyDeactivate(String reason) {
        log.error("[Emergency] Deactivation triggered: {}", reason);
        try {
            role.set(Role.DEACTIVATING);
            try { eventLog.switchAllToStandby(); } catch (Exception e) {
                log.error("[Emergency] Failed to switch EventLog to standby", e);
            }
            // 停止 Kafka 消费者，防止消费者在 shouldBeRunning=false 时仍在运行导致数据丢失
            try {
                kafkaConsumerStartupService.stopConsumers();
                log.info("[Emergency] Kafka consumers stopped successfully");
            } catch (Exception e) {
                log.error("[Emergency] Failed to stop Kafka consumers", e);
            }
            // 停止复制发送线程，避免 STANDBY 状态下继续发送事件
            try {
                eventLogReplicationSender.stopSending();
                log.info("[Emergency] EventLog replication sender stopped successfully");
            } catch (Exception e) {
                log.error("[Emergency] Failed to stop EventLog replication sender", e);
            }
            role.set(Role.STANDBY);
            leaderElection.startDeactivate();
            leaderElection.completeDeactivate();
            notifyStandbyTakeover(reason);
            // 注意：紧急降级不清除 Redis PRIMARY 标志，保留以便重启后自动恢复
            log.info("[Emergency] Instance {} emergency deactivated to STANDBY", instanceId);
            return true;
        } catch (Exception e) {
            log.error("[Emergency] Emergency deactivation failed for instance {}", instanceId, e);
            return false;
        }
    }

    // ==================== 状态查询 ====================

    /**
     * 是否可以处理订单（只有 PRIMARY 才能处理）
     */
    public boolean isActive() {
        return role.get() == Role.PRIMARY || (!haEnabled);
    }

    public boolean isPrimary() {
        return role.get() == Role.PRIMARY;
    }

    public boolean isStandby() {
        return role.get() == Role.STANDBY;
    }

    public boolean isTransitioning() {
        Role r = role.get();
        return r == Role.ACTIVATING || r == Role.DEACTIVATING;
    }

    public String getCurrentRole() {
        return role.get().name();
    }

    public String getRole() {
        return role.get().name();
    }

    public String getInstanceId() {
        return instanceId;
    }

    public HAStatus getStatus() {
        Role r = role.get();
        String statusStr = switch (r) {
            case PRIMARY -> "ACTIVE";
            case STANDBY -> "INACTIVE";
            case ACTIVATING -> "ACTIVATING";
            case DEACTIVATING -> "DEACTIVATING";
        };
        return new HAStatus(r.name(), instanceId, statusStr);
    }

    /**
     * 计划内停机时清除 Redis PRIMARY 标志（由 GracefulShutdown 调用）
     * 防止 systemd stop 后重启误自动激活
     */
    public void persistRoleOnShutdown() {
        persistRole("STANDBY");
        log.info("[Shutdown] Cleared PRIMARY role flag for instance={}", instanceId);
    }

    /** 仅用于测试 */
    public void forceSetStatus(String roleName, boolean active, String statusValue) {
        try {
            role.set(Role.valueOf(roleName));
        } catch (IllegalArgumentException e) {
            // 兼容旧测试传入 "PRIMARY"/"STANDBY" 字符串
            role.set("PRIMARY".equals(roleName) ? Role.PRIMARY : Role.STANDBY);
        }
        log.warn("Status forcibly set to: role={}", roleName);
    }

    // ==================== 私有方法 ====================

    private void recoverFromBackup() {
        log.info("开始快速数据恢复...");
        List<String> symbols = getActiveSymbols();
        if (symbols.isEmpty()) {
            log.info("无需恢复的交易对");
            return;
        }
        Map<String, FastRecoveryService.FastRecoveryResult> results =
                recoveryService.recoverAllSymbols(symbols);
        long successCount = results.values().stream().filter(FastRecoveryService.FastRecoveryResult::isSuccess).count();
        long totalDuration = results.values().stream().mapToLong(FastRecoveryService.FastRecoveryResult::getDurationMs).sum();
        if (successCount == symbols.size()) {
            log.info("快速数据恢复完成: symbols={}, total_duration={}ms", symbols.size(), totalDuration);
        } else {
            results.entrySet().stream()
                    .filter(e -> !e.getValue().isSuccess())
                    .forEach(e -> log.error("恢复失败: symbol={}, reason={}", e.getKey(), e.getValue().getMessage()));
            throw new RuntimeException(String.format("数据恢复失败: %d/%d", successCount, symbols.size()));
        }
    }

    private List<String> getActiveSymbols() {
        List<String> symbols = symbolConfigService.getActiveSymbolIds();
        log.info("Active symbols from config: {}", symbols);
        return symbols;
    }

    private void createFinalSnapshot() {
        log.info("Creating final snapshot before deactivation...");
        Map<String, MatchEngine> engines = orderBookService.getAllEngines();
        int count = 0;
        for (Map.Entry<String, MatchEngine> entry : engines.entrySet()) {
            try {
                snapshotService.snapshot(entry.getKey(), entry.getValue());
                count++;
            } catch (Exception e) {
                log.error("Failed to create final snapshot for symbol: {}", entry.getKey(), e);
            }
        }
        log.info("Final snapshot created for {} symbols", count);
    }

    private void registerHeartbeat() {
        try {
            redisTemplate.opsForValue().set("matching:ha:heartbeat:" + instanceId,
                    String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("Failed to register heartbeat", e);
        }
    }

    private void unregisterHeartbeat() {
        try {
            redisTemplate.delete("matching:ha:heartbeat:" + instanceId);
        } catch (Exception e) {
            log.warn("Failed to unregister heartbeat", e);
        }
    }

    private void persistRole(String roleName) {
        try {
            String key = ROLE_KEY_PREFIX + instanceId;
            if ("PRIMARY".equals(roleName)) {
                redisTemplate.opsForValue().set(key, "PRIMARY");
            } else {
                redisTemplate.delete(key);
            }
            log.debug("[HA] Persisted role={} for instance={}", roleName, instanceId);
        } catch (Exception e) {
            log.warn("[HA] Failed to persist role={}: {}", roleName, e.getMessage());
        }
    }

    private void notifyStandbyTakeover(String reason) {
        try {
            String msg = String.format("{\"reason\":\"%s\",\"timestamp\":%d,\"fromInstance\":\"%s\"}",
                    reason, System.currentTimeMillis(), instanceId);
            redisTemplate.opsForValue().set("matching:ha:takeover:notification", msg);
            log.info("Takeover notification sent: {}", msg);
        } catch (Exception e) {
            log.error("Failed to send takeover notification", e);
        }
    }

    private long readGlobalSentSeq() {
        try {
            String value = redisTemplate.opsForValue().get(EventLogReplicationSender.SENT_SEQ_KEY);
            return value != null ? Long.parseLong(value) : 0;
        } catch (Exception e) {
            log.warn("Failed to read global sent seq, using 0", e);
            return 0;
        }
    }
}
