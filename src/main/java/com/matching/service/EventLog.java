package com.matching.service;

import com.matching.dto.OrderBookEntry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.Serializable;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EventLog 抽象服务类，定义事件日志的基本接口和数据结构
 */
public abstract class EventLog {
    protected static final Logger log = LoggerFactory.getLogger(EventLog.class);

    protected final StringRedisTemplate redisTemplate;
    protected final AtomicLong globalSeq = new AtomicLong(0);

    public EventLog(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public abstract void init();

    @PreDestroy
    public abstract void shutdown();

    /**
     * 批量追加事件
     */
    public abstract long appendBatch(String symbolId, List<OrderBookEntry> addedOrders,
                                    List<String> removedOrderIds,
                                    List<MatchResultEntry> matchResults);

    /**
     * 直接写入已有seq的事件（从实例复制用，保留主实例的原始seq）
     */
    public abstract void appendReplicated(Event event);

    /**
     * 读取事件
     */
    public abstract List<Event> readEvents(String symbolId, long afterSeq);

    /**
     * 读取指定序列号的事件（用于切换时发送剩余事件）
     */
    public abstract Event readEventBySeq(long seq);

    /**
     * 批量读取指定范围内的所有事件，单次扫描实现，避免 O(n²)。
     * 用于 EventLogReplicationSender 批量发送。
     *
     * @param fromSeq 起始序列号（包含）
     * @param toSeq   结束序列号（包含）
     * @return 范围内的事件列表（按 seq 升序排列）
     */
    public abstract List<Event> readEventsInRange(long fromSeq, long toSeq);

    /**
     * 读取指定交易对在指定seq之后的所有事件
     * 由于全局seq设计，同一交易对的seq可能不连续，需要过滤
     */
    public List<Event> readEventsForSymbol(String symbolId, long afterSeq) {
        List<Event> allEvents = readEvents(symbolId, afterSeq); // 读取该交易对的所有事件
        List<Event> filteredEvents = new java.util.ArrayList<>();

        for (Event event : allEvents) {
            if (event.getSeq() > afterSeq && symbolId.equals(event.getSymbolId())) {
                filteredEvents.add(event);
            }
        }

        // 按seq排序确保顺序正确
        filteredEvents.sort((Event e1, Event e2) -> Long.compare(e1.getSeq(), e2.getSeq()));

        log.debug("Read {} filtered events for symbol: {} after seq: {}",
                filteredEvents.size(), symbolId, afterSeq);
        return filteredEvents;
    }

    /**
     * 写入快照
     */
    public abstract void writeSnapshot(String symbolId, long seq, List<OrderBookEntry> allOrders);

    /**
     * 读取快照
     */
    public abstract Snapshot readSnapshot(String symbolId);

    /**
     * 读取最后发送的序列号
     */
    public long readLastSentSeq(String symbolId) {
        try {
            String key = "matching:eventlog:sent:" + symbolId;
            String value = redisTemplate.opsForValue().get(key);
            return value != null ? Long.parseLong(value) : 0;
        } catch (Exception e) {
            log.warn("Failed to read last sent seq for symbol: {}", symbolId, e);
            return 0;
        }
    }

    /**
     * 写入最后发送的序列号
     */
    public void writeLastSentSeq(String symbolId, long seq) {
        try {
            String key = "matching:eventlog:sent:" + symbolId;
            redisTemplate.opsForValue().set(key, String.valueOf(seq));
            log.debug("Updated last sent seq for {}: {}", symbolId, seq);
        } catch (Exception e) {
            log.warn("Failed to write last sent seq for symbol: {}, seq: {}", symbolId, seq, e);
        }
    }

    /**
     * 检查数据一致性：验证本地EventLog与主实例的同步状态
     * 考虑全局seq设计，需要检查该交易对的数据完整性
     */
    public ConsistencyCheckResult checkDataConsistency(String symbolId) {
        try {
            // 获取该交易对的最大seq
            long localMaxSeq = getMaxSeqForSymbol(symbolId);
            long lastSentSeq = readLastSentSeq(symbolId);

            // 检查是否有数据缺失
            boolean hasGap = localMaxSeq < lastSentSeq;
            // 检查是否有未同步的数据
            boolean hasUnsyncedData = localMaxSeq > lastSentSeq;

            ConsistencyStatus status;
            if (hasGap) {
                status = ConsistencyStatus.DATA_MISSING;
            } else if (hasUnsyncedData) {
                status = ConsistencyStatus.UNSYNCED_DATA;
            } else {
                status = ConsistencyStatus.CONSISTENT;
            }

            return new ConsistencyCheckResult(status, localMaxSeq, lastSentSeq,
                    localMaxSeq - lastSentSeq);
        } catch (Exception e) {
            log.error("Failed to check data consistency for {}", symbolId, e);
            return new ConsistencyCheckResult(ConsistencyStatus.CHECK_FAILED, 0, 0, 0);
        }
    }

    /**
     * 获取指定交易对的最大序列号
     */
    protected long getMaxSeqForSymbol(String symbolId) {
        try {
            List<Event> events = readEvents(symbolId, 0);
            long maxSeq = 0;
            for (Event event : events) {
                if (symbolId.equals(event.getSymbolId())) {
                    maxSeq = Math.max(maxSeq, event.getSeq());
                }
            }
            return maxSeq;
        } catch (Exception e) {
            log.warn("Failed to get max seq for symbol: {}", symbolId, e);
            return 0;
        }
    }

    /**
     * 获取安全的恢复起始点，考虑全局seq设计，需要检查该交易对的数据完整性
     */
    public long getSafeRecoveryStartSeq(String symbolId) {
        ConsistencyCheckResult result = checkDataConsistency(symbolId);
        switch (result.status) {
            case CONSISTENT:
                return result.lastSentSeq;
            case UNSYNCED_DATA:
                // 有未同步数据，从最后同步点开始
                return result.lastSentSeq;
            case DATA_MISSING:
                // 数据缺失，需要从更早的点开始或全量恢复
                log.warn("Data missing detected for {}, local max seq: {}, last sent: {}",
                        symbolId, result.getLocalSeq(), result.getLastSentSeq());
                return 0; // 全量恢复
            default:
                return 0; // 全量恢复
        }
    }

    /**
     * 获取当前序列号
     */
    public long currentSeq() {
        return globalSeq.get();
    }

    /**
     * 获取当前序列号 - 兼容性方法
     */
    public long getCurrentSeq() {
        return currentSeq();
    }

    /**
     * 获取本地EventLog的最大序列号
     */
    public abstract long getMaxLocalSeq();

    /**
     * 同步序列号 - 主从切换时确保序列号连续性
     * 读取本地EventLog最大seq，与当前globalSeq比较，取较大值
     */
    public long syncSequenceOnActivation() {
        try {
            long maxLocalSeq = getMaxLocalSeq();
            long currentGlobalSeq = globalSeq.get();

            // 取较大值，确保序列号不回退
            long newSeq = Math.max(maxLocalSeq, currentGlobalSeq);

            if (newSeq > currentGlobalSeq) {
                globalSeq.set(newSeq);
                log.info("Sequence synced on activation: local_max={}, current={}, new={}",
                        maxLocalSeq, currentGlobalSeq, newSeq);
            } else {
                log.info("Sequence already up-to-date: local_max={}, current={}",
                        maxLocalSeq, currentGlobalSeq);
            }

            return newSeq;
        } catch (Exception e) {
            log.error("Failed to sync sequence on activation", e);
            return globalSeq.get();
        }
    }

    /**
     * 清理symbol数据
     */
    public void clearSymbol(String symbolId) {
        try {
            String key = "matching:eventlog:sent:" + symbolId;
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Failed to clear symbol data for: {}", symbolId, e);
        }
    }

    // ==================== 数据类定义 ====================

    public static class Event implements Serializable {
        private static final long serialVersionUID = 1L;
        private long seq;
        private String symbolId;
        private List<OrderBookEntry> addedOrders;
        private List<String> removedOrderIds;
        private List<MatchResultEntry> matchResults;

        public Event() {}

        public Event(long seq, String symbolId, List<OrderBookEntry> addedOrders,
                     List<String> removedOrderIds, List<MatchResultEntry> matchResults) {
            this.seq = seq;
            this.symbolId = symbolId;
            this.addedOrders = addedOrders;
            this.removedOrderIds = removedOrderIds;
            this.matchResults = matchResults;
        }

        public long getSeq() { return seq; }
        public void setSeq(long seq) { this.seq = seq; }
        public String getSymbolId() { return symbolId; }
        public void setSymbolId(String symbolId) { this.symbolId = symbolId; }
        public List<OrderBookEntry> getAddedOrders() { return addedOrders; }
        public void setAddedOrders(List<OrderBookEntry> addedOrders) { this.addedOrders = addedOrders; }
        public List<String> getRemovedOrderIds() { return removedOrderIds; }
        public void setRemovedOrderIds(List<String> removedOrderIds) { this.removedOrderIds = removedOrderIds; }
        public List<MatchResultEntry> getMatchResults() { return matchResults; }
        public void setMatchResults(List<MatchResultEntry> matchResults) { this.matchResults = matchResults; }

        // 兼容性方法
        public List<String> getCancelledOrderIds() { return removedOrderIds; }
        public void setCancelledOrderIds(List<String> cancelledOrderIds) { this.removedOrderIds = cancelledOrderIds; }

        public byte[] getSyncPayload() {
            // 简化实现，返回空数组
            return new byte[0];
        }
    }

    public static class MatchResultEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        private String uidKey;
        private byte[] payload;

        public MatchResultEntry() {}

        public MatchResultEntry(String uidKey, byte[] payload) {
            this.uidKey = uidKey;
            this.payload = payload;
        }

        public String getUidKey() {
            return uidKey;
        }

        public void setUidKey(String uidKey) {
            this.uidKey = uidKey;
        }

        public byte[] getPayload() {
            return payload;
        }

        public void setPayload(byte[] payload) {
            this.payload = payload;
        }
    }

    public static class Snapshot implements Serializable {
        private static final long serialVersionUID = 1L;
        private long seq;
        private String symbolId;
        private List<OrderBookEntry> allOrders;

        public Snapshot() {}

        public Snapshot(long seq, String symbolId, List<OrderBookEntry> allOrders) {
            this.seq = seq;
            this.symbolId = symbolId;
            this.allOrders = allOrders;
        }

        public long getSeq() {
            return seq;
        }

        public void setSeq(long seq) {
            this.seq = seq;
        }

        public String getSymbolId() {
            return symbolId;
        }

        public void setSymbolId(String symbolId) {
            this.symbolId = symbolId;
        }

        public List<OrderBookEntry> getAllOrders() {
            return allOrders;
        }

        public void setAllOrders(List<OrderBookEntry> allOrders) {
            this.allOrders = allOrders;
        }
    }

    /**
     * 数据一致性检查结果
     */
    public static class ConsistencyCheckResult {
        private ConsistencyStatus status;
        private long localSeq;
        private long lastSentSeq;
        private long seqDiff;

        public ConsistencyCheckResult(ConsistencyStatus status, long localSeq, long lastSentSeq, long seqDiff) {
            this.status = status;
            this.localSeq = localSeq;
            this.lastSentSeq = lastSentSeq;
            this.seqDiff = seqDiff;
        }

        public ConsistencyStatus getStatus() {
            return status;
        }

        public void setStatus(ConsistencyStatus status) {
            this.status = status;
        }

        public long getLocalSeq() {
            return localSeq;
        }

        public void setLocalSeq(long localSeq) {
            this.localSeq = localSeq;
        }

        public long getLastSentSeq() {
            return lastSentSeq;
        }

        public void setLastSentSeq(long lastSentSeq) {
            this.lastSentSeq = lastSentSeq;
        }

        public long getSeqDiff() {
            return seqDiff;
        }

        public void setSeqDiff(long seqDiff) {
            this.seqDiff = seqDiff;
        }
    }

    /**
     * 一致性状态
     */
    public enum ConsistencyStatus {
        CONSISTENT,     // 一致
        DATA_MISSING,   // 数据缺失
        UNSYNCED_DATA,  // 有未同步数据
        CHECK_FAILED    // 检查失败
    }
}

