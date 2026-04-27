package com.matching.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TestChronicleQueueConfig 单元测试
 *
 * 注意：Chronicle Queue 5.19.46 与 JDK 21 存在兼容性问题
 * (sun.nio.ch.FileChannelImpl.unmap0 在 JDK 21 中已移除)，
 * 因此只测试不依赖 Chronicle Queue 底层初始化的纯逻辑。
 * 升级到 Chronicle Queue 5.24.4+ 后可开放完整测试。
 */
class TestChronicleQueueConfigTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("TestChronicleQueueFactory 构造函数正常")
    void testFactoryConstruction() {
        TestChronicleQueueConfig.TestChronicleQueueFactory factory =
                new TestChronicleQueueConfig.TestChronicleQueueFactory(
                        tempDir.toString(), "test-node", "STANDBY");
        assertNotNull(factory);
    }

    @Test
    @DisplayName("hasQueueManager 初始状态返回 false")
    void testHasQueueManagerInitiallyFalse() {
        TestChronicleQueueConfig.TestChronicleQueueFactory factory =
                new TestChronicleQueueConfig.TestChronicleQueueFactory(
                        tempDir.toString(), "test-node", "STANDBY");
        assertFalse(factory.hasQueueManager("BTCUSDT"));
        assertFalse(factory.hasQueueManager("ETHUSDT"));
    }

    @Test
    @DisplayName("getAllSymbols 初始状态为空")
    void testGetAllSymbolsInitiallyEmpty() {
        TestChronicleQueueConfig.TestChronicleQueueFactory factory =
                new TestChronicleQueueConfig.TestChronicleQueueFactory(
                        tempDir.toString(), "test-node", "STANDBY");
        assertTrue(factory.getAllSymbols().isEmpty());
    }

    @Test
    @DisplayName("shutdown 对空工厂不抛异常")
    void testShutdownOnEmptyFactory() {
        TestChronicleQueueConfig.TestChronicleQueueFactory factory =
                new TestChronicleQueueConfig.TestChronicleQueueFactory(
                        tempDir.toString(), "test-node", "STANDBY");
        assertDoesNotThrow(factory::shutdown);
        assertTrue(factory.getAllSymbols().isEmpty());
    }

    @Test
    @DisplayName("getQueueManager symbolId 为 null 抛出 IllegalArgumentException")
    void testGetQueueManagerNullSymbol() {
        TestChronicleQueueConfig.TestChronicleQueueFactory factory =
                new TestChronicleQueueConfig.TestChronicleQueueFactory(
                        tempDir.toString(), "test-node", "STANDBY");
        assertThrows(IllegalArgumentException.class, () -> factory.getQueueManager(null));
    }

    @Test
    @DisplayName("getQueueManager symbolId 为空字符串抛出 IllegalArgumentException")
    void testGetQueueManagerEmptySymbol() {
        TestChronicleQueueConfig.TestChronicleQueueFactory factory =
                new TestChronicleQueueConfig.TestChronicleQueueFactory(
                        tempDir.toString(), "test-node", "STANDBY");
        assertThrows(IllegalArgumentException.class, () -> factory.getQueueManager(""));
        assertThrows(IllegalArgumentException.class, () -> factory.getQueueManager("  "));
    }

    @Test
    @DisplayName("TestChronicleQueueConfig Bean 配置类可正常实例化")
    void testConfigClassInstantiation() {
        TestChronicleQueueConfig config = new TestChronicleQueueConfig();
        assertNotNull(config);
    }
}
