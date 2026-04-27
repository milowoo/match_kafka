package com.matching.service.chronicle;

import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.TimeUnit;

public class ChronicleQueueManager {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChronicleQueueManager.class);
    private final String queuePath;
    private final String instanceId;
    private final AtomicReference<RoleState> roleState = new AtomicReference<>(RoleState.STANDBY);

    // 使用读写锁保护资源切换操作
    private final ReentrantReadWriteLock roleLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = roleLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = roleLock.writeLock();

    private ChronicleQueue queue;
    private volatile ExcerptAppender appender;
    private ExcerptTailer tailer;

    // 角色状态枚举，提供更清晰的状态管理
    public enum RoleState {
        STANDBY,
        SWITCHING_TO_PRIMARY,
        PRIMARY,
        SWITCHING_TO_STANDBY,
        ERROR
    }

    public ChronicleQueueManager(String queuePath, String instanceId, String initialRole) {
        this.queuePath = queuePath;
        this.instanceId = instanceId;

        // 设置初始角色状态
        if ("PRIMARY".equals(initialRole)) {
            this.roleState.set(RoleState.PRIMARY);
        } else {
            this.roleState.set(RoleState.STANDBY);
        }
    }

    public void initialize() {
        writeLock.lock();
        try {
            log.info("Initializing Chronicle Queue for instance: {}, initial role: {}",
                    instanceId, roleState.get());

            // 使用单独的队列实例，确保完全隔离
            queue = ChronicleQueue.single(queuePath);

            // 为每个实例创建唯一的tailer名称，避免冲突
            String tailerName = "tailer-" + instanceId + "-" + System.currentTimeMillis();
            tailer = queue.createTailer(tailerName);

            if (roleState.get() == RoleState.PRIMARY) {
                appender = queue.createAppender();
                log.info("Chronicle Queue initialized as PRIMARY, instance: {}, tailer: {}", instanceId, tailerName);
            } else {
                log.info("Chronicle Queue initialized as STANDBY, instance: {}, tailer: {}", instanceId, tailerName);
            }
        } catch (Exception e) {
            roleState.set(RoleState.ERROR);
            log.error("Failed to initialize Chronicle Queue for instance: {}", instanceId, e);
            throw new RuntimeException("Chronicle Queue initialization failed for instance: " + instanceId, e);
        } finally {
            writeLock.unlock();
        }
    }

    public void shutdown() {
        writeLock.lock();
        try {
            log.info("Shutting down Chronicle Queue for instance: {}", instanceId);

            if (appender != null) {
                appender.close();
                appender = null;
            }
            if (tailer != null) {
                tailer.close();
                tailer = null;
            }
            if (queue != null) {
                queue.close();
                queue = null;
            }

            roleState.set(RoleState.STANDBY);
            log.info("Chronicle Queue closed successfully");
        } catch (Exception e) {
            log.warn("Error closing Chronicle Queue", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 切换到主实例 - 使用原子操作和锁保护
     */
    public boolean switchToPrimary() {
        return switchToPrimary(30, TimeUnit.SECONDS);
    }

    /**
     * 切换到主实例 - 带超时控制
     */
    public boolean switchToPrimary(long timeout, TimeUnit unit) {
        log.info("Attempting to switch instance {} to PRIMARY, current state: {}",
                instanceId, roleState.get());

        try {
            // 尝试获取写锁，带超时
            if (!writeLock.tryLock(timeout, unit)) {
                log.warn("Failed to acquire write lock for PRIMARY switch within {} {}", timeout, unit);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for write lock");
            return false;
        }

        try {
            // 检查当前状态是否允许切换
            RoleState currentState = roleState.get();
            if (currentState != RoleState.STANDBY) {
                log.warn("Cannot switch to PRIMARY from state: {}", currentState);
                return false;
            }

            // 设置中间状态
            if (!roleState.compareAndSet(RoleState.STANDBY, RoleState.SWITCHING_TO_PRIMARY)) {
                log.warn("Failed to set SWITCHING_TO_PRIMARY state, current: {}", roleState.get());
                return false;
            }

            try {
                // 获取appender资源
                ExcerptAppender newAppender = queue.createAppender();

                // 原子性地更新appender和状态
                this.appender = newAppender;
                roleState.set(RoleState.PRIMARY);

                log.info("Successfully switched instance {} to PRIMARY", instanceId);
                return true;
            } catch (Exception e) {
                // 资源获取失败，回滚状态
                roleState.set(RoleState.STANDBY);
                log.error("Failed to acquire appender, rolled back to STANDBY", e);
                throw new RuntimeException("Failed to switch to primary", e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 切换到从实例 - 使用原子操作和锁保护
     */
    public boolean switchToStandby() {
        return switchToStandby(30, TimeUnit.SECONDS);
    }

    /**
     * 切换到从实例 - 带超时控制
     */
    public boolean switchToStandby(long timeout, TimeUnit unit) {
        log.info("Attempting to switch instance {} to STANDBY, current state: {}",
                instanceId, roleState.get());

        try {
            // 尝试获取写锁，带超时
            if (!writeLock.tryLock(timeout, unit)) {
                log.warn("Failed to acquire write lock for STANDBY switch within {} {}", timeout, unit);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for write lock");
            return false;
        }

        try {
            // 检查当前状态是否允许切换
            RoleState currentState = roleState.get();
            if (currentState != RoleState.PRIMARY) {
                log.warn("Cannot switch to STANDBY from state: {}", currentState);
                return false;
            }

            // 设置中间状态
            if (!roleState.compareAndSet(RoleState.PRIMARY, RoleState.SWITCHING_TO_STANDBY)) {
                log.warn("Failed to set SWITCHING_TO_STANDBY state, current: {}", roleState.get());
                return false;
            }

            try {
                // 安全关闭appender
                if (appender != null) {
                    appender.close();
                    appender = null;
                }

                // 更新状态
                roleState.set(RoleState.STANDBY);

                log.info("Successfully switched instance {} to STANDBY", instanceId);
                return true;
            } catch (Exception e) {
                // 关闭失败，但仍然设置为STANDBY状态
                roleState.set(RoleState.STANDBY);
                appender = null; // 确保appender被清空
                log.error("Error closing appender during STANDBY switch, but state updated", e);
                throw new RuntimeException("Failed to switch to standby", e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 检查是否为主实例 - 使用读锁保护
     */
    public boolean isPrimary() {
        readLock.lock();
        try {
            return roleState.get() == RoleState.PRIMARY;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 获取当前角色 - 线程安全
     */
    public String getRole() {
        RoleState state = roleState.get();
        return switch (state) {
            case PRIMARY -> "PRIMARY";
            case STANDBY -> "STANDBY";
            case SWITCHING_TO_PRIMARY -> "SWITCHING_TO_PRIMARY";
            case SWITCHING_TO_STANDBY -> "SWITCHING_TO_STANDBY";
            case ERROR -> "ERROR";
            default -> "UNKNOWN";
        };
    }

    /**
     * 获取详细的角色状态
     */
    public RoleState getRoleState() {
        return roleState.get();
    }

    /**
     * 检查是否正在切换状态
     */
    public boolean isSwitching() {
        RoleState state = roleState.get();
        return state == RoleState.SWITCHING_TO_PRIMARY || state == RoleState.SWITCHING_TO_STANDBY;
    }

    /**
     * 获取appender - 使用读锁保护
     */
    public ExcerptAppender getAppender() {
        readLock.lock();
        try {
            return appender;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 获取tailer - 线程安全
     */
    public ExcerptTailer getTailer() {
        return tailer; // tailer是线程安全的，不需要锁保护
    }

    /**
     * 获取queue - 线程安全
     */
    public ChronicleQueue getQueue() {
        return queue; // queue是线程安全的，不需要锁保护
    }

    /**
     * 强制重置到错误状态 - 用于异常恢复
     */
    public void forceErrorState() {
        writeLock.lock();
        try {
            roleState.set(RoleState.ERROR);
            log.warn("Instance {} forced to ERROR state", instanceId);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 获取实例ID
     */
    public String getInstanceId() {
        return instanceId;
    }
}