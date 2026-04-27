package com.matching.config;

import com.matching.service.chronicle.ChronicleQueueFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 测试专用的Chronicle Queue配置 确保测试环境中每个测试都有完全独立的队列实例
 */
@TestConfiguration
@Slf4j
@Profile("test")
public class TestChronicleQueueConfig {

    @Value("${matching.eventlog.dir:/tmp/matching-test-eventlog}")
    private String baseQueuePath;

    @Value("${matching.ha.instance-id:test-node}")
    private String instanceId;

    @Value("${matching.ha.role:STANDBY}")
    private String initialRole;

    /**
     * 测试专用的Chronicle Queue工厂 为每个测试创建完全隔离的队列实例
     */
    @Bean
    @Primary
    public ChronicleQueueFactory testChronicleQueueFactory() {
        log.info("Creating test Chronicle Queue Factory with enhanced isolation");

        // 为测试创建唯一的基础路径，包含测试类名和时间戳
        String testBasePath = baseQueuePath + "/test-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
        String testInstanceId = instanceId + "-test-" + System.currentTimeMillis();
        log.info("Test Chronicle Queue Factory - basePath: {}, instanceId: {}", testBasePath, testInstanceId);

        return new TestChronicleQueueFactory(testBasePath, testInstanceId, initialRole);
    }

    /**
     * 测试专用的Chronicle Queue工厂实现 提供额外的隔离保证
     */
    public static class TestChronicleQueueFactory extends ChronicleQueueFactory {

        private final String testBasePath;
        private final String testInstanceId;
        private final String testInitialRole;
        private final java.util.concurrent.ConcurrentHashMap<String, com.matching.service.chronicle.ChronicleQueueManager> testQueueManagers =
                new java.util.concurrent.ConcurrentHashMap<>();

        public TestChronicleQueueFactory(String testBasePath, String testInstanceId, String testInitialRole) {
            this.testBasePath = testBasePath;
            this.testInstanceId = testInstanceId;
            this.testInitialRole = testInitialRole;
        }

        @Override
        public com.matching.service.chronicle.ChronicleQueueManager getQueueManager(String symbolId) {
            if (symbolId == null || symbolId.trim().isEmpty()) {
                throw new IllegalArgumentException("SymbolId cannot be null or empty");
            }

            // 检查是否已存在
            com.matching.service.chronicle.ChronicleQueueManager existing = testQueueManagers.get(symbolId);
            if (existing != null) {
                return existing;
            }

            // 为测试环境创建高度隔离的路径
            String testSymbolPath = testBasePath + "/symbol-" + symbolId.toLowerCase() +
                    "-" + System.nanoTime() + "-" + Thread.currentThread().getId();
            String testSymbolInstanceId = testInstanceId + "-" + symbolId.toLowerCase() +
                    "-" + System.nanoTime();

            log.info("Creating test Chronicle Queue manager for symbol: {}, path: {}, instanceId: {}",
                    symbolId, testSymbolPath, testSymbolInstanceId);

            try {
                // 确保目录存在
                java.nio.file.Path queueDir = java.nio.file.Paths.get(testSymbolPath);
                java.nio.file.Files.createDirectories(queueDir);

                com.matching.service.chronicle.ChronicleQueueManager manager =
                        new com.matching.service.chronicle.ChronicleQueueManager(
                                testSymbolPath, testSymbolInstanceId, testInitialRole);

                // 初始化管理器
                manager.initialize();

                // 缓存管理器
                testQueueManagers.put(symbolId, manager);

                log.info("Test Chronicle Queue manager created successfully for symbol: {}", symbolId);
                return manager;
            } catch (Exception e) {
                log.error("Failed to create test Chronicle Queue manager for symbol: {}", symbolId, e);
                throw new RuntimeException("Failed to create test Chronicle Queue manager for symbol: " + symbolId, e);
            }
        }

        @Override
        public boolean hasQueueManager(String symbolId) {
            return testQueueManagers.containsKey(symbolId);
        }

        @Override
        public java.util.Set<String> getAllSymbols() {
            return new java.util.HashSet<>(testQueueManagers.keySet());
        }

        @Override
        public void shutdown() {
            log.info("Test Chronicle Queue Factory shutdown - cleaning {} instances", testQueueManagers.size());
            for (java.util.Map.Entry<String, com.matching.service.chronicle.ChronicleQueueManager> entry : testQueueManagers.entrySet()) {
                try {
                    entry.getValue().shutdown();
                } catch (Exception e) {
                    log.warn("Error shutting down test queue manager for symbol: {}", entry.getKey(), e);
                }
            }
            testQueueManagers.clear();
        }
    }
}