package com.matching.config;

import com.matching.service.UnifiedChronicleQueueEventLog;
import com.matching.service.EventLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@Slf4j
public class EventLogConfig {

    /**
     * 统一的 Chronicle Queue EventLog Bean - 唯一的 EventLog 实现
     */
    @Bean
    @Primary
    public EventLog eventLog(StringRedisTemplate redisTemplate) {
        log.info("Initializing UnifiedChronicleQueueEventLog (single queue for all symbols)");
        return new UnifiedChronicleQueueEventLog(redisTemplate);
    }

    /**
     * EventLog 类型信息 Bean，用于监控和状态查询
     */
    @Bean
    public EventLogInfo eventLogInfo() {
        return new EventLogInfo();
    }

    public static class EventLogInfo {
        private final String type = "chronicle-queue";
        private final long createdTime;

        public EventLogInfo() {
            this.createdTime = System.currentTimeMillis();
        }

        public String getType() {
            return type;
        }

        public long getCreatedTime() {
            return createdTime;
        }

        public boolean isChronicleQueue() {
            return true;
        }
    }
}