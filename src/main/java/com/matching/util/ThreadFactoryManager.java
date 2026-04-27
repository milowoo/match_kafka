package com.matching.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一线程工厂管理器 解决高并发启动时线程池命名冲突问题
 */
@Component
@Slf4j
public class ThreadFactoryManager {

    // 全局线程计数器
    private static final AtomicLong GLOBAL_THREAD_COUNTER = new AtomicLong(0);

    // 线程组计数器
    private static final ConcurrentHashMap<String, AtomicInteger> GROUP_COUNTERS = new ConcurrentHashMap<>();

    // 实例ID，确保多实例部署时的唯一性
    private static final String INSTANCE_ID = generateInstanceId();

    /**
     * 创建命名线程工厂
     * @param groupName - 线程组名称
     * @param daemon - 是否为守护线程
     * @return: ThreadFactory
     */
    public static ThreadFactory createNamedThreadFactory(String groupName, boolean daemon) {
        return createNamedThreadFactory(groupName, daemon, Thread.NORM_PRIORITY);
    }

    /**
     * 创建命名线程工厂
     * @param groupName - 线程组名称
     * @param daemon - 是否为守护线程
     * @param priority - 线程优先级
     * @return: ThreadFactory
     */
    public static ThreadFactory createNamedThreadFactory(String groupName, boolean daemon, int priority) {
        // 获取或创建线程组计数器
        AtomicInteger groupCounter = GROUP_COUNTERS.computeIfAbsent(groupName, k -> new AtomicInteger(0));
        return new NamedThreadFactory(groupName, groupCounter, daemon, priority);
    }

    /**
     * 创建单例线程工厂（每个组只有一个线程）
     */
    public static ThreadFactory createSingletonThreadFactory(String threadName, boolean daemon) {
        return r -> {
            Thread t = new Thread(r, threadName + "-" + INSTANCE_ID);
            t.setDaemon(daemon);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        };
    }

    /**
     * 生成实例ID
     * 使用进程ID + 启动时间戳 + 随机数确保唯一性
     */
    private static String generateInstanceId() {
        long pid = ProcessHandle.current().pid();
        long timestamp = System.currentTimeMillis();
        int random = (int) (Math.random() * 1000);
        return String.format("%d-%d-%03d", pid, timestamp % 100000, random);
    }

    /**
     * 获取线程统计信息
     */
    public static ThreadStats getThreadStats() {
        return new ThreadStats(
                GLOBAL_THREAD_COUNTER.get(),
                GROUP_COUNTERS.size(),
                INSTANCE_ID,
                new ConcurrentHashMap<>(GROUP_COUNTERS)
        );
    }

    /**
     * 重置统计信息（仅用于测试）
     */
    public static void resetStats() {
        GLOBAL_THREAD_COUNTER.set(0);
        GROUP_COUNTERS.clear();
        log.warn("Thread factory statistics reset");
    }

    /**
     * 命名线程工厂实现
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final String groupName;
        private final AtomicInteger groupCounter;
        private final boolean daemon;
        private final int priority;
        private final String prefix;

        public NamedThreadFactory(String groupName, AtomicInteger groupCounter, boolean daemon, int priority) {
            this.groupName = groupName;
            this.groupCounter = groupCounter;
            this.daemon = daemon;
            this.priority = priority;
            this.prefix = groupName + "-" + INSTANCE_ID + "-";
        }

        @Override
        public Thread newThread(Runnable r) {
            long globalId = GLOBAL_THREAD_COUNTER.incrementAndGet();
            int groupId = groupCounter.incrementAndGet();

            String threadName = prefix + groupId + "-" + globalId;
            Thread t = new Thread(r, threadName);
            t.setDaemon(daemon);
            t.setPriority(priority);

            // 设置未捕获异常处理器
            t.setUncaughtExceptionHandler((Thread thread, Throwable ex) -> {
                log.error("Uncaught exception in thread: {}", thread.getName(), ex);
            });

            log.debug("Created thread: {} (group: {}, daemon: {}, priority: {})",
                    threadName, groupName, daemon, priority);
            return t;
        }
    }

    /**
     * 线程统计信息
     */
    public static class ThreadStats {
        public final long totalThreadsCreated;
        public final int activeGroups;
        public final String instanceId;
        public final ConcurrentHashMap<String, AtomicInteger> groupCounters;

        public ThreadStats(long totalThreadsCreated, int activeGroups, String instanceId,
                           ConcurrentHashMap<String, AtomicInteger> groupCounters) {
            this.totalThreadsCreated = totalThreadsCreated;
            this.activeGroups = activeGroups;
            this.instanceId = instanceId;
            this.groupCounters = groupCounters;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ThreadStats{");
            sb.append("totalCreated=").append(totalThreadsCreated);
            sb.append(", activeGroups=").append(activeGroups);
            sb.append(", instanceId='").append(instanceId).append('\'');
            sb.append(", groups={");

            groupCounters.forEach((group, counter) ->
                    sb.append(group).append("=").append(counter.get()).append(", "));

            if (!groupCounters.isEmpty()) {
                sb.setLength(sb.length() - 2); // 移除最后的", "
            }
            sb.append("}}");
            return sb.toString();
        }
    }
}