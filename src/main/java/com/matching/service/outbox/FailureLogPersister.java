package com.matching.service.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;

/**
 * 失败日志持久化服务 - 负责将失败的数据持久化到磁盘
 */
@Component
@Slf4j
public class FailureLogPersister {

    @Value("${matching.outbox.failure-log-dir:/tmp/matching/failure-log}")
    private String failureLogDir;

    public FailureLogPersister() {
    }

    /**
     * 持久化失败数据到磁盘（带数据脱敏）
     */
    public void persistFailureLog(String uidKey, byte[] payload) throws IOException {
        Path logDir = Paths.get(failureLogDir);
        if (!Files.exists(logDir)) {
            Files.createDirectories(logDir);
            setDirectoryPermissions(logDir);
        }

        Path logFile = logDir.resolve("failed-outbox-" + LocalDate.now().toString() + ".log");

        // Base64编码用于磁盘持久化
        String base64Data = java.util.Base64.getEncoder().encodeToString(payload);

        String logLine = String.format("%d,%s,%s",
                System.currentTimeMillis(),
                uidKey,
                base64Data);

        Files.write(logFile, logLine.getBytes(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        setFilePermissions(logFile);
    }

    private void setDirectoryPermissions(Path logDir) {
        try {
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
            );
        } catch (UnsupportedOperationException e) {
            log.debug("POSIX permissions not supported on this system");
        }
    }

    private void setFilePermissions(Path logFile) {
        try {
            Files.setPosixFilePermissions(logFile,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            log.debug("POSIX permissions not supported on this system");
        } catch (IOException e) {
            log.warn("Failed to set file permissions: {}", e.getMessage());
        }
    }
}