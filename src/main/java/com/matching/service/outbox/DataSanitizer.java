package com.matching.service.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 数据脱敏服务 - 负责敏感数据的脱敏处理
 */
@Component
@Slf4j
public class DataSanitizer {

    @Value("${matching.outbox.data-sanitization.enabled:true}")
    private boolean dataSanitizationEnabled;

    @Value("${matching.outbox.max-log-entry-size:10000}")
    private int maxLogEntrySize;

    /**
     * 数据脱敏处理 - 隐藏敏感信息
     */
    public String sanitize(String originalData) {
        if (originalData == null || originalData.isEmpty()) {
            return "";
        }

        // 如果关闭脱敏，直接返回原数据（仅截断长度）
        if (!dataSanitizationEnabled) {
            return truncateData(originalData);
        }

        try {
            String sanitized = originalData;

            // 替换敏感数值信息
            sanitized = sanitized.replaceAll("\"price\":\"[^\"]*\"", "\"price\":\"***\"");
            sanitized = sanitized.replaceAll("\"quantity\":\"[^\"]*\"", "\"quantity\":\"***\"");
            sanitized = sanitized.replaceAll("\"amount\":\"[^\"]*\"", "\"amount\":\"***\"");
            sanitized = sanitized.replaceAll("\"accountId\":\"\\d*", "\"accountId\":\"***");
            sanitized = sanitized.replaceAll("\"orderId\":\"\\d*", "\"orderId\":\"***");

            return truncateData(sanitized);
        } catch (Exception e) {
            log.warn("Failed to sanitize log data, using placeholder", e);
            return "[SANITIZATION_FAILED]";
        }
    }

    /**
     * 截断超长日志数据
     */
    private String truncateData(String data) {
        if (data.length() > maxLogEntrySize) {
            data = data.substring(0, maxLogEntrySize) + "...[TRUNCATED]";
        }
        return data.replace("\n", "\\n");
    }
}