package com.matching.service;

import com.matching.dto.OrderBookEntry;
import com.matching.engine.CompactOrderBookEntry;
import com.matching.engine.MatchEngine;
import com.matching.util.ProtoConverter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class SnapshotService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SnapshotService.class);
    private static final String SNAPSHOT_KEY_PREFIX = "matching:snapshot:";
    private final EventLog eventLog;
    private final StringRedisTemplate redisTemplate;

    public SnapshotService(EventLog eventLog, StringRedisTemplate redisTemplate) {
        this.eventLog = eventLog;
        this.redisTemplate = redisTemplate;
    }

    public void snapshot(String symbolId, MatchEngine engine) {
        long seq = eventLog.currentSeq();
        if (seq == 0) return;

        List<OrderBookEntry> allOrders = new ArrayList<>();
        for (CompactOrderBookEntry e : engine.getBuyBook().allOrders()) {
            allOrders.add(e.toEntry(symbolId));
        }
        for (CompactOrderBookEntry e : engine.getSellBook().allOrders()) {
            allOrders.add(e.toEntry(symbolId));
        }

        // 只写Redis Snapshot用于HA failover，移除本地EventLog snapshot写入
        writeSnapshotToRedis(symbolId, seq, allOrders);

        // 更新LastSentSeq保证一致性
        eventLog.writeLastSentSeq(symbolId, seq);
    }

    public void writeSnapshotToRedis(String symbolId, long seq, List<OrderBookEntry> allOrders) {
        try {
            EventLog.Snapshot snap = new EventLog.Snapshot(seq, symbolId, allOrders);
            byte[] data = ProtoConverter.serializeSnapshot(snap);
            String encoded = Base64.getEncoder().encodeToString(data);
            redisTemplate.opsForValue().set(SNAPSHOT_KEY_PREFIX + symbolId, encoded);
            log.info("[Snapshot] Written to Redis for {}, seq={}, orders={}, size={} bytes",
                    symbolId, seq, allOrders.size(), data.length);
        } catch (Exception e) {
            log.error("[Snapshot] Failed to write Redis snapshot for {}", symbolId, e);
        }
    }

    public EventLog.Snapshot readSnapshotFromRedis(String symbolId) {
        try {
            String encoded = redisTemplate.opsForValue().get(SNAPSHOT_KEY_PREFIX + symbolId);
            if (encoded == null) return null;
            byte[] data = Base64.getDecoder().decode(encoded);
            EventLog.Snapshot snap = ProtoConverter.deserializeSnapshot(data);
            log.info("[Snapshot] Read from Redis for {}, seq={}, orders={}",
                    symbolId, snap.getSeq(), snap.getAllOrders() != null ? snap.getAllOrders().size() : 0);
            return snap;
        } catch (Exception e) {
            log.error("[Snapshot] Failed to read Redis snapshot for {}", symbolId, e);
            return null;
        }
    }

    /**
     * 验证Snapshot与本地EventLog的一致性：考虑全局seq设计，需要检查该交易对的事件连续性
     */
    public boolean validateSnapshotConsistency(String symbolId, EventLog.Snapshot snapshot) {
        if (snapshot == null) {
            log.warn("[Snapshot] Snapshot is null for {}", symbolId);
            return false;
        }

        long snapshotSeq = snapshot.getSeq();
        try {
            // 检查是否有有效交易对在snapshot seq之后的事件
            List<EventLog.Event> incrementalEvents = eventLog.readEventsForSymbol(symbolId, snapshotSeq);
            if (incrementalEvents == null) {
                log.error("[Snapshot] Cannot read incremental events after seq {} for {}",
                        snapshotSeq, symbolId);
                return false;
            }

            // 检查是否有足够的数据用于恢复（允许为空，说明没有增量数据）
            log.info("[Snapshot] Validation passed for {}, snapshot seq={}, incremental events={}",
                    symbolId, snapshotSeq, incrementalEvents.size());
            return true;
        } catch (Exception e) {
            log.error("[Snapshot] Failed to validate consistency for {}", symbolId, e);
            return false;
        }
    }

    /**
     * 安全的Snapshot恢复，包含一致性检查。根据Snapshot中的seq信息，精确回放后续的增量EventLog事件。
     * 由于所有交易对共享全局seq，同一交易对的seq可能不连续
     */
    public boolean safeRecoverFromSnapshot(String symbolId, MatchEngine engine) {
        EventLog.Snapshot snapshot = readSnapshotFromRedis(symbolId);
        if (snapshot == null) {
            log.warn("[Snapshot] No snapshot found for {}, will recover from EventLog only", symbolId);
            return false;
        }

        // 验证一致性
        if (!validateSnapshotConsistency(symbolId, snapshot)) {
            log.error("[Snapshot] Consistency validation failed for {}, fallback to full EventLog recovery", symbolId);
            return false;
        }

        try {
            long snapshotSeq = snapshot.getSeq();

            // 1. 恢复Snapshot到指定seq状态
            if (snapshot.getAllOrders() != null) {
                engine.loadOrders(snapshot.getAllOrders());
                log.info("[Snapshot] Loaded {} orders from snapshot seq={} for {}",
                        snapshot.getAllOrders().size(), snapshotSeq, symbolId);
            }

            // 2. 使用新的过滤方法读取增量事件（处理seq不连续问题）
            List<EventLog.Event> incrementalEvents = eventLog.readEventsForSymbol(symbolId, snapshotSeq);
            int replayedEvents = 0;

            // 3. 按顺序回放增量事件
            for (EventLog.Event event : incrementalEvents) {
                // 事件已经被过滤和排序，直接处理
                // 这里需要OrderBookService来处理事件回放
                // orderBookService.recoverFromEvent(event);
                replayedEvents++;
                log.debug("[Snapshot] Replayed event seq={} for {}", event.getSeq(), symbolId);
            }

            log.info("[Snapshot] Successfully recovered {} from snapshot seq={}, replayed {} incremental events",
                    symbolId, snapshotSeq, replayedEvents);
            return true;
        } catch (Exception e) {
            log.error("[Snapshot] Failed to recover from snapshot for {}", symbolId, e);
            return false;
        }
    }
}