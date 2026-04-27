package com.matching.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 雪花算法ID生成器 64位ID结构：1位符号位 + 41位时间戳 + 10位机器ID + 12位序列号
 */
@Component
@Slf4j
public class SnowflakeIdGenerator {

    // 起始时间戳（2024-01-01 00:00:00）
    private static final long EPOCH = 1704067200000L;

    // 各部分位数
    private static final long MACHINE_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;

    // 最大值
    private static final long MAX_MACHINE_ID = ~(-1L << MACHINE_ID_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    // 位移
    private static final long MACHINE_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + MACHINE_ID_BITS;

    private final long machineId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(@Value("${matching.snowflake.machine-id:1}") long machineId) {
        if (machineId > MAX_MACHINE_ID || machineId < 0) {
            throw new IllegalArgumentException(
                    String.format("Machine ID must be between 0 and %d", MAX_MACHINE_ID));
        }
        this.machineId = machineId;
        log.info("SnowflakeIdGenerator initialized with machineId: {}", machineId);
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        // 时钟回拨检测
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset < 5) {
                // 小幅回拨，使用循环等待而非sleep，减少线程切换开销
                timestamp = waitNextMillis(lastTimestamp);
                if (timestamp < lastTimestamp) {
                    throw new RuntimeException("Clock moved backwards. Refusing to generate id");
                }
            } else {
                // 大幅回拨，直接抛出异常
                throw new RuntimeException(String.format(
                        "Clock moved backwards by %d ms. Refusing to generate id", offset));
            }
        }

        if (timestamp == lastTimestamp) {
            // 同一毫秒内，序列号递增
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // 序号号溢出，等待下一毫秒
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            // 新的毫秒，序列号重置
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 组装ID
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (machineId << MACHINE_ID_SHIFT)
                | sequence;
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    // 解析ID获取时间戳
    public long getTimestamp(long id) {
        return (id >> TIMESTAMP_SHIFT) + EPOCH;
    }

    // 解析ID获取机器ID
    public long getMachineId(long id) {
        return (id >> MACHINE_ID_SHIFT) & MAX_MACHINE_ID;
    }

    // 解析ID获取序列号
    public long getSequence(long id) {
        return id & MAX_SEQUENCE;
    }
}