package com.matching.engine;

import lombok.extern.slf4j.Slf4j;
import java.util.ArrayDeque;

@Slf4j
public class EntryPool {

    public static final int DEFAULT_POOL_SIZE = 20000;
    private final ArrayDeque<CompactOrderBookEntry> pool;

    public EntryPool(int capacity) {
        pool = new ArrayDeque<>(capacity);
        for (int i = 0; i < capacity; i++) {
            pool.offer(new CompactOrderBookEntry());
        }
    }

    public CompactOrderBookEntry acquire() {
        CompactOrderBookEntry entry = pool.poll();
        return entry != null ? entry : new CompactOrderBookEntry();
    }

    public void release(CompactOrderBookEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Cannot release null entry");
        }

        if (entry.prev != null || entry.next != null) {
            throw new IllegalStateException(
                "Cannot release entry still in OrderList, orderId: " + entry.orderId +
                ", prev: " + (entry.prev != null ? entry.prev.orderId : "null") +
                ", next: " + (entry.next != null ? entry.next.orderId : "null")
            );
        }

        entry.reset();

        // 限制pool大小防止内存溢出
        if (pool.size() < DEFAULT_POOL_SIZE) {
            pool.offer(entry);
        } else {
            log.warn("EntryPool size limit reached, discarding entry, pool.size(): {}", pool.size());
        }
    }

    public int available() {
        return pool.size();
    }
}