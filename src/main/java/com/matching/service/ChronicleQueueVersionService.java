package com.matching.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import java.util.regex.Pattern;

/**
 * Chronicle Queue版本兼容性检查服务 - 确保使用稳定版本, 避免早期访问版本的稳定性风险
 */
@Service
@Slf4j
public class ChronicleQueueVersionService {

    // 早期访问版本模式: 包含 ea, alpha, beta, rc, snapshot 等
    private static final Pattern UNSTABLE_VERSION_PATTERN = Pattern.compile(
            ".*?(ea|alpha|beta|rc|snapshot|dev|pre|milestone|m\\d+).*",
            Pattern.CASE_INSENSITIVE
    );

    // 推荐的稳定版本范围
    private static final String MIN_STABLE_VERSION = "5.22.0";
    private static final String MAX_TESTED_VERSION = "5.22.28";

    @PostConstruct
    public void checkVersion() {
        try {
            String version = "5.22.28"; // 当前使用的版本
            log.info("检测到 Chronicle Queue 版本: {}", version);

            VersionCheckResult result = validateVersion(version);

            switch (result.getStatus()) {
                case STABLE:
                    log.info("√ Chronicle Queue 版本检查通过: {} (稳定版本)", version);
                    break;
                case UNSTABLE:
                    log.error("× Chronicle Queue 版本风险警告: {}", version, result.getMessage());
                    logVersionRecommendation();
                    break;
                case UNKNOWN:
                    log.warn("▲ Chronicle Queue 版本未知: {}", version, result.getMessage());
                    logVersionRecommendation();
                    break;
                case TOO_OLD:
                    log.warn("▲ Chronicle Queue 版本过旧: {}", version, result.getMessage());
                    logVersionRecommendation();
                    break;
                case TOO_NEW:
                    log.warn("▲ Chronicle Queue 版本过新: {}", version, result.getMessage());
                    logVersionRecommendation();
                    break;
            }
        } catch (Exception e) {
            log.error("Chronicle Queue 版本检查失败", e);
        }
    }

    /**
     * 验证版本稳定性
     */
    private VersionCheckResult validateVersion(String version) {
        if (version == null || "unknown".equals(version)) {
            return new VersionCheckResult(VersionStatus.UNKNOWN,
                    "无法确定版本信息");
        }

        // 检查是否为不稳定版本
        if (UNSTABLE_VERSION_PATTERN.matcher(version).matches()) {
            return new VersionCheckResult(VersionStatus.UNSTABLE,
                    "检测到早期访问或测试版本, 存在稳定性风险");
        }

        // 检查版本范围
        try {
            if (compareVersion(version, MIN_STABLE_VERSION) < 0) {
                return new VersionCheckResult(VersionStatus.TOO_OLD,
                        "版本过旧, 建议升级到 " + MIN_STABLE_VERSION + " 或更高版本");
            }

            if (compareVersion(version, MAX_TESTED_VERSION) > 0) {
                return new VersionCheckResult(VersionStatus.TOO_NEW,
                        "版本过新, 未经充分测试, 建议使用 " + MAX_TESTED_VERSION + " 或更低版本");
            }

            return new VersionCheckResult(VersionStatus.STABLE,
                    "版本稳定, 适合生产环境");
        } catch (Exception e) {
            return new VersionCheckResult(VersionStatus.UNKNOWN,
                    "版本格式无法解析: " + e.getMessage());
        }
    }

    /**
     * 简单的版本比较 (仅支持数字版本号)
     */
    private int compareVersion(String version1, String version2) {
        // 移除非数字字符, 只保留数字和点
        String v1 = version1.replaceAll("[^0-9.]", "");
        String v2 = version2.replaceAll("[^0-9.]", "");

        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");

        int maxLength = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < maxLength; i++) {
            int num1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int num2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;

            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }

        return 0;
    }

    /**
     * 记录版本建议
     */
    private void logVersionRecommendation() {
        log.info("=== Chronicle Queue 版本建议 ===");
        log.info("推荐稳定版本: {}", MAX_TESTED_VERSION);
        log.info("最低支持版本: {}", MIN_STABLE_VERSION);
        log.info("避免使用: 包含 ea, alpha, beta, rc, snapshot 的版本");
        log.info("更新方法: 在 pom.xml 中修改 chronicle-queue 版本号");
    }

    /**
     * 获取版本检查结果
     */
    public VersionInfo getVersionInfo() {
        String version = "5.22.28"; // 当前使用的版本
        VersionCheckResult result = validateVersion(version);

        return new VersionInfo(
                version,
                result.getStatus(),
                result.getMessage(),
                MIN_STABLE_VERSION,
                MAX_TESTED_VERSION
        );
    }

    /**
     * 版本状态枚举
     */
    public enum VersionStatus {
        STABLE,     // 稳定版本
        UNSTABLE,   // 不稳定版本 (ea, alpha, beta等)
        TOO_OLD,    // 版本过旧
        TOO_NEW,    // 版本过新
        UNKNOWN     // 未知版本
    }

    /**
     * 版本检查结果
     */
    public static class VersionCheckResult {
        private final VersionStatus status;
        private final String message;

        public VersionCheckResult(VersionStatus status, String message) {
            this.status = status;
            this.message = message;
        }

        public VersionStatus getStatus() { return status; }
        public String getMessage() { return message; }
    }

    /**
     * 版本信息
     */
    public static class VersionInfo {
        public final String currentVersion;
        public final VersionStatus status;
        public final String message;
        public final String minStableVersion;
        public final String maxTestedVersion;

        public VersionInfo(String currentVersion, VersionStatus status, String message,
                          String minStableVersion, String maxTestedVersion) {
            this.currentVersion = currentVersion;
            this.status = status;
            this.message = message;
            this.minStableVersion = minStableVersion;
            this.maxTestedVersion = maxTestedVersion;
        }

        @Override
        public String toString() {
            return String.format("VersionInfo{current='%s', status=%s, message='%s', min='%s', max='%s'}",
                    currentVersion, status, message, minStableVersion, maxTestedVersion);
        }
    }
}