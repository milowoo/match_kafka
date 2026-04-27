package com.matching.service.chronicle;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Chronicle Queue事务管理器 负责处理序列号管理、事务控制和检查点机制
 */
@Component
public class ChronicleQueueTransactionManager {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChronicleQueueTransactionManager.class);

    @Value("${matching.eventlog.transaction.enabled:true}")
    private boolean transactionEnabled;

    @Value("${matching.eventlog.checkpoint.interval-ms:5000}")
    private long checkpointInterval;

    private volatile long lastCheckpointTime = 0;

    /**
     * 原子性回滚全局序列号
     */
    public void rollbackGlobalSeq(AtomicLong globalSeq, long failedSeq) {
        // 使用CAS确保原子性回滚
        if (!globalSeq.compareAndSet(failedSeq, failedSeq - 1)) {
            // 如果CAS失败，说明有其他线程已经修改了序列号
            // 记录警告但不抛出异常，因为这种情况下系统仍然是一致的
            log.warn("Failed to rollback global sequence from {} to {}, current value: {}",
                    failedSeq, failedSeq - 1, globalSeq.get());
        }
    }

    /**
     * 更新已提交序列号
     */
    public void updateCommittedSeq(AtomicLong committedSeq, long seq) {
        if (!transactionEnabled) {
            return;
        }

        // 使用自旋锁确保committedSeq单调递增
        long current;
        do {
            current = committedSeq.get();
            if (seq <= current) {
                // 序列号不需要更新
                return;
            }
        } while (!committedSeq.compareAndSet(current, seq));
    }

    /**
     * 检查是否需要创建检查点
     */
    public void checkpointIfNeeded(ChronicleQueueComponentManager.SymbolQueueComponents components,
                                  AtomicLong globalSeq) {
        long now = System.currentTimeMillis();
        if (now - lastCheckpointTime > checkpointInterval) {
            try {
                components.writer.writeCheckpoint(globalSeq.get(), components.queueManager.getInstanceId());
                lastCheckpointTime = now;
                log.debug("Checkpoint created at seq: {} for instance: {}",
                        globalSeq.get(), components.queueManager.getInstanceId());
            } catch (Exception e) {
                // 检查点失败不应该影响主流程
                log.warn("Failed to create checkpoint for instance: {}",
                        components.queueManager.getInstanceId(), e);
            }
        }
    }

    /**
     * 预分配序列号
     */
    public long allocateSequence(AtomicLong globalSeq) {
        return globalSeq.incrementAndGet();
    }

    /**
     * 检查事务是否启用
     */
    public boolean isTransactionEnabled() {
        return transactionEnabled;
    }

    /**
     * 获取检查点间隔
     */
    public long getCheckpointInterval() {
        return checkpointInterval;
    }

    /**
     * 获取最后检查点时间
     */
    public long getLastCheckpointTime() {
        return lastCheckpointTime;
    }

    /**
     * 手动创建检查点
     */
    public void createCheckpoint(ChronicleQueueComponentManager.SymbolQueueComponents components,
                                long seq) {
        try {
            components.writer.writeCheckpoint(seq, components.queueManager.getInstanceId());
            lastCheckpointTime = System.currentTimeMillis();
            log.info("Manual checkpoint created at seq: {} for instance: {}",
                    seq, components.queueManager.getInstanceId());
        } catch (Exception e) {
            log.error("Failed to create manual checkpoint for instance: {}",
                    components.queueManager.getInstanceId(), e);
            throw new RuntimeException("Failed to create checkpoint", e);
        }
    }

    /**
     * 重置检查点时间
     */
    public void resetCheckpointTime() {
        lastCheckpointTime = System.currentTimeMillis();
    }
}