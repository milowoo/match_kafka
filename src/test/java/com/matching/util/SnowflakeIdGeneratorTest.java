package com.matching.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SnowflakeIdGeneratorTest {

    private SnowflakeIdGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new SnowflakeIdGenerator(1);
    }

    @Test
    void testBasicIdGeneration() {
        long id1 = generator.nextId();
        long id2 = generator.nextId();

        assertTrue(id1 > 0);
        assertTrue(id2 > 0);
        assertNotEquals(id1, id2);
        assertTrue(id2 > id1);
    }

    @Test
    void testIdUniqueness() {
        Set<Long> ids = new HashSet<>();
        int count = 10000;

        for (int i = 0; i < count; i++) {
            long id = generator.nextId();
            assertTrue(ids.add(id), "Duplicate ID found: " + id);
        }
        assertEquals(count, ids.size());
    }

    @Test
    void testConcurrentIdGeneration() throws InterruptedException {
        int threadCount = 10;
        int idsPerThread = 1000;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        Set<Long> allIds = new HashSet<>();
        AtomicInteger duplicateCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    Set<Long> threadIds = new HashSet<>();
                    for (int j = 0; j < idsPerThread; j++) {
                        long id = generator.nextId();
                        if (!threadIds.add(id)) duplicateCount.incrementAndGet();
                        synchronized (allIds) {
                            if (!allIds.add(id)) duplicateCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(0, duplicateCount.get(), "Found duplicate IDs in concurrent generation");
        assertEquals(threadCount * idsPerThread, allIds.size());
    }

    @Test
    void testComponentExtraction() {
        long id = generator.nextId();
        long timestamp = generator.getTimestamp(id);
        long machineId = generator.getMachineId(id);
        long sequence = generator.getSequence(id);

        assertTrue(timestamp > 0);
        assertEquals(1L, machineId);
        assertTrue(sequence >= 0);
    }

    @Test
    void testInvalidMachineId() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(1024));
    }

    @Test
    void testPerformance() {
        int count = 100000;
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            generator.nextId();
        }

        long duration = System.currentTimeMillis() - startTime;
        assertTrue(duration < 1000, "ID generation too slow: " + duration + " ms for " + count + " IDs");
        System.out.printf("Generated %d IDs in %d ms (%.2f ids/sec)%n",
                count, duration, (double) count / duration * 1000);
    }
}
