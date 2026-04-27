package com.matching.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class IdempotentService {

    private static final String KEY_PREFIX = "matching:dup:";
    private static final String KEY_SUFFIX = ":";

    @Value("${matching.idempotent.ttl-seconds:3600}")
    private long ttlSeconds;

    @Value("${matching.idempotent.local-cache.size:100000}")
    private int localCacheSize;

    @Value("${matching.idempotent.fail-strategy:FAIL_SAFE}")
    private String failStrategy;

    private final StringRedisTemplate redisTemplate;
    private final MatchingMetrics metrics;

    // Per-symbol LRU caches, created lazily.
    // 使用ConcurrentHashMap保证线程安全，内部使用同步的LinkedHashMap
    private final Map<String, Map<String, Boolean>> localCaches = new java.util.concurrent.ConcurrentHashMap<>();

    public IdempotentService(StringRedisTemplate redisTemplate, MatchingMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
    }

    /**
     * Check if orderId has been processed.
     * Local LRU cache (0.001ms), covers normal dedup + short-interval Kafka redelivery.
     * Redis key (0.15ms), covers crash recovery when local cache is lost.
     */
    public boolean isProcessed(String symbolId, String orderId) {
        // L1: local cache
        Map<String, Boolean> cache = getLocalCache(symbolId);
        synchronized (cache) {
            if (cache.containsKey(orderId)) {
                metrics.recordIdempotentCheck(symbolId, true, "local");
                return true;
            }
        }

        // L2: Redis (only on cache miss - crash recovery scenario)
        try {
            boolean exists = Boolean.TRUE.equals(redisTemplate.hasKey(key(symbolId, orderId)));
            if(exists){
                metrics.recordIdempotentCheck(symbolId, exists, "redis");
            }

            return exists;
        } catch (Exception e) {
            log.error("Failed to check idempotent in Redis for orderId: {}, symbolId: {}", orderId, symbolId, e);
            //redis失败了， 是严重生产问题， 直接返回失败
            return true;
        }
    }

    /**
     * Mark orders in local cache immediately (called from Disruptor thread after matching).
     * Redis mark is deferred to flush pipeline.
     */
    public void markLocal(String symbolId, String... orderIds) {
        if (orderIds == null || orderIds.length == 0) return;
        Map<String, Boolean> cache = getLocalCache(symbolId);
        synchronized (cache) {
            for (String orderId : orderIds) {
                cache.put(orderId, Boolean.TRUE);
            }
        }
    }

    /**
     * Batch mark orderIds in Redis using pipeline (1 RTT for all).
     * Called during flush, in the same future as Redis orderbook sync.
     */
    public void markProcessed(String symbolId, String... orderIds) {
        if (orderIds == null || orderIds.length == 0) return;
        try {
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    for (String orderId : orderIds) {
                        operations.opsForValue().set(key(symbolId, orderId), "1", ttlSeconds, TimeUnit.SECONDS);
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to mark idempotent for symbol: " + symbolId, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Boolean> getLocalCache(String symbolId) {
        // 创建线程安全的LRU缓存
        return (Map<String, Boolean>) localCaches.computeIfAbsent(symbolId, k ->
                new LinkedHashMap<String, Boolean>(localCacheSize, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                        return size() > localCacheSize;
                    }
                }
        );
    }

    /**
     * 批量清理本地缓存中的幂等标记
     */
    public void clearLocalMarks(String symbolId, String... orderIds) {
        if (orderIds == null || orderIds.length == 0) return;
        Map<String, Boolean> cache = getLocalCache(symbolId);
        synchronized (cache) {
            for (String orderId : orderIds) {
                cache.remove(orderId);
            }
        }
        metrics.recordIdempotentMarkClearing(symbolId, orderIds.length, "local");
        log.debug("Cleared {} local idempotent marks, symbolId: {}", orderIds.length, symbolId);
    }

    /**
     * 清理Redis中的幂等标记 (flush失败恢复时使用)
     * 注意: 这是一个昂贵的操作, 只在必要时使用
     */
    public void clearRedisMarks(String symbolId, String... orderIds) {
        if (orderIds == null || orderIds.length == 0) return;
        try {
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    for (String orderId : orderIds) {
                        operations.delete(key(symbolId, orderId));
                    }
                    return null;
                }
            });
            metrics.recordIdempotentMarkClearing(symbolId, orderIds.length, "redis");
            log.info("Cleared Redis idempotent marks, symbolId: {}", symbolId);
        } catch (Exception e) {
            log.error("Failed to clear Redis idempotent marks for symbolId: {}, orderIds: {}", symbolId, orderIds, e);
            throw new RuntimeException("Failed to clear Redis idempotent marks", e);
        }
    }

    /**
     * 完整清理幂等标记 (本地缓存 + Redis) 用于flush失败后的完整恢复
     */
    public void clearAllMarks(String symbolId, String... orderIds) {
        if (orderIds == null || orderIds.length == 0) return;
        // 先清理本地缓存 (快速操作)
        clearLocalMarks(symbolId, orderIds);
        // 再清理Redis (可能失败, 但要尝试)
        try {
            clearRedisMarks(symbolId, orderIds);
            metrics.recordIdempotentMarkClearing(symbolId, orderIds.length, "complete");
        } catch (Exception e) {
            metrics.recordIdempotentMarkClearing(symbolId, orderIds.length, "local_only");
            log.warn("Failed to clear Redis marks during complete cleanup, " +
                    "orders may be rejected until TTL expires: {}, symbolId: {}", orderIds, symbolId, e.getMessage());
        }
    }

    /**
     * 获取指定symbol的本地缓存大小
     */
    public int getLocalCacheSize(String symbolId) {
        Map<String, Boolean> cache = localCaches.get(symbolId);
        if (cache == null) {
            return 0;
        }
        synchronized (cache) {
            return cache.size();
        }
    }

    private String key(String symbolId, String orderId) {
        return KEY_PREFIX + symbolId + KEY_SUFFIX + orderId;
    }
}