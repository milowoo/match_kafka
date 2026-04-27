package com.matching.controller;

import com.matching.config.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/ops/redis")
@Slf4j
public class RedisInfoController {

    @Autowired
    private RedisConfig redisConfig;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 获取Redis连接信息
     */
    @GetMapping("/info")
    public Map<String, Object> getRedisInfo() {
        Map<String, Object> info = new HashMap<>();
        try {
            info.put("mode", redisConfig.getRedisMode());
            info.put("connection", redisConfig.getConnectionInfo());
            info.put("status", "CONNECTED");

            // 测试连接
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            info.put("ping", pong);

            // 获取一些基本信息
            try {
                info.put("dbSize", redisTemplate.getConnectionFactory().getConnection().dbSize());
            } catch (Exception e) {
                // Sentinel和Cluster模式在特定情况下可能不支持dbSize命令
                info.put("dbSize", "N/A (mode: " + redisConfig.getRedisMode() + ")");
            }
        } catch (Exception e) {
            log.error("Failed to get Redis info", e);
            info.put("status", "ERROR");
            info.put("error", e.getMessage());
        }
        return info;
    }

    /**
     * 测试Redis连接
     */
    @GetMapping("/test")
    public Map<String, Object> testConnection() {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();
        try {
            // 写入测试
            String testKey = "matching:test:" + System.currentTimeMillis();
            String testValue = "test-value-" + System.currentTimeMillis();
            redisTemplate.opsForValue().set(testKey, testValue);

            // 读取测试
            String retrievedValue = redisTemplate.opsForValue().get(testKey);
            redisTemplate.delete(testKey);

            long duration = System.currentTimeMillis() - startTime;
            result.put("status", "SUCCESS");
            result.put("mode", redisConfig.getRedisMode());
            result.put("connection", redisConfig.getConnectionInfo());
            result.put("writeRead", testValue.equals(retrievedValue));
            result.put("duration", duration + "ms");
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Redis connection test failed", e);
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
            result.put("duration", duration + "ms");
        }
        return result;
    }

    /**
     * 获取Redis统计信息
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        try {
            stats.put("mode", redisConfig.getRedisMode());
            // 获取匹配相关的key统计
            stats.put("symbolConfigs", getKeyCount("matching:symbol:config"));
            stats.put("orderBooks", getKeyCount("matching:orderbook:*"));
            stats.put("sequences", getKeyCount("matching:seq:*"));
        } catch (Exception e) {
            log.error("Failed to get Redis stats", e);
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    /**
     * 获取指定模式的Key数量
     */
    private long getKeyCount(String pattern) {
        try {
            String mode = redisConfig.getRedisMode();
            if ("CLUSTER".equals(mode)) {
                // 集群模式下不支持keys命令，返回估算值
                return -1; // 表示不支持
            } else if ("SENTINEL".equals(mode)) {
                // Sentinel模式下，keys命令可用，但需要注意性能影响
                // 在生产环境中，建议使用SCAN替代KEYS
                return redisTemplate.keys(pattern).size();
            } else {
                // Standalone模式
                return redisTemplate.keys(pattern).size();
            }
        } catch (Exception e) {
            log.warn("Failed to get key count for pattern: {}", pattern, e);
            return -1;
        }
    }
}