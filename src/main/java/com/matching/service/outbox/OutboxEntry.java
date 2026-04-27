package com.matching.service.outbox;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outbox条目 - 表示一个待发送的Protobuf消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEntry {
    private String uidKey;
    private byte[] payload;
    private long timestamp;
    private int retryCount;

    public OutboxEntry(String uidKey, byte[] payload) {
        this.uidKey = uidKey;
        this.payload = payload;
        this.timestamp = System.currentTimeMillis();
        this.retryCount = 0;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public boolean isExpired(long maxAgeMs) {
        return System.currentTimeMillis() - timestamp > maxAgeMs;
    }
}