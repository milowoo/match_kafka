package com.matching.service;

import com.matching.constant.RejectReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;

@Service
@Slf4j
public class RateLimiter {

    @Value("50")
    private int maxOrdersPerSecond;

    @Value("1000")
    private long windowMs;

    // key: accountId:symbolId -> sliding window timestamps
    // 使用类型安全的ConcurrentHashMap
    private final Map<String, ArrayDeque<Long>> windows = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Returns: null if allowed, error message if rate limited
     */
    public String check(long accountId, String symbolId) {
        if (symbolId == null || symbolId.trim().isEmpty()) {
            log.warn("Invalid symbolId for rate limit check: {}", symbolId);
            return RejectReason.RATE_LIMIT_EXCEEDED + ": invalid symbol";
        }

        String key = accountId + ":" + symbolId;
        long now = System.currentTimeMillis();

        ArrayDeque<Long> window = getOrCreateWindow(key);

        if (window == null) {
            // 防御性检查：如果无法创建窗口，拒绝请求
            log.error("Failed to create rate limit window for key: {}", key);
            return RejectReason.RATE_LIMIT_EXCEEDED + ": system error";
        }

        synchronized (window) {
            // Evict expired entries
            while (!window.isEmpty()) {
                Long firstTimestamp = window.peekFirst();
                if (firstTimestamp == null || now - firstTimestamp > windowMs) {
                    window.pollFirst();
                } else {
                    break;
                }
            }

            if (window.size() >= maxOrdersPerSecond) {
                return RejectReason.RATE_LIMIT_EXCEEDED + ": " + maxOrdersPerSecond + " orders per " + windowMs + "ms";
            }

            window.addLast(now);
            return null;
        }
    }

    private ArrayDeque<Long> getOrCreateWindow(String key) {
        try {
            return windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        } catch (Exception e) {
            log.error("Failed to create rate limit window for key: {}", key, e);
            return null;
        }
    }

    /**
     * Cleanup empty windows to prevent memory leak from inactive accounts.
     */
    @Scheduled(fixedDelay = 60000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, ArrayDeque<Long>>> it = windows.entrySet().iterator();
        int removed = 0;

        while (it.hasNext()) {
            Map.Entry<String, ArrayDeque<Long>> entry = it.next();
            ArrayDeque<Long> window = entry.getValue();

            if (window == null) {
                // 防御性检查：移除null值
                it.remove();
                removed++;
                continue;
            }

            synchronized (window) {
                // Evict expired
                while (!window.isEmpty() && now - window.peekFirst() > windowMs) {
                    window.pollFirst();
                }
                if (window.isEmpty()) {
                    it.remove();
                    removed++;
                }
            }
        }

        if (removed > 0) {
            log.debug("RateLimiter cleanup: removed {} stale entries, remaining: {}", removed, windows.size());
        }
    }

    /**
     * 获取指定账户和交易对的当前请求数
     */
    public int getCurrentRequestCount(long accountId, String symbolId) {
        if (symbolId == null || symbolId.trim().isEmpty()) {
            return 0;
        }

        String key = accountId + ":" + symbolId;
        ArrayDeque<Long> window = windows.get(key);

        if (window == null) {
            return 0;
        }

        synchronized (window) {
            long now = System.currentTimeMillis();
            // 清理过期数据
            while (!window.isEmpty()) {
                Long firstTimestamp = window.peekFirst();
                if (firstTimestamp == null || now - firstTimestamp > windowMs) {
                    window.pollFirst();
                } else {
                    break;
                }
            }
            return window.size();
        }
    }

    /**
     * 清理指定账户的限流记录（用于测试或管理）
     */
    public void clearRateLimit(long accountId, String symbolId) {
        if (symbolId == null || symbolId.trim().isEmpty()) {
            return;
        }

        String key = accountId + ":" + symbolId;
        ArrayDeque<Long> window = windows.get(key);

        if (window != null) {
            synchronized (window) {
                window.clear();
            }
            log.debug("Cleared rate limit for key: {}", key);
        }
    }

    /**
     * 获取当前缓存的窗口数量（用于监控）
     */
    public int getActiveWindowCount() {
        return windows.size();
    }
}