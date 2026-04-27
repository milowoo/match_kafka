package com.matching.service;

/**
 * Chronicle Queue写入异常 - 用于封装Chronicle Queue写入过程中的异常, 提供更清晰的异常处理
 */
public class ChronicleQueueWriteException extends RuntimeException {

    private final String symbolId;
    private final long sequence;

    public ChronicleQueueWriteException(String message) {
        super(message);
        this.symbolId = null;
        this.sequence = -1;
    }

    public ChronicleQueueWriteException(String message, Throwable cause) {
        super(message, cause);
        this.symbolId = null;
        this.sequence = -1;
    }

    public ChronicleQueueWriteException(String symbolId, long sequence, String message, Throwable cause) {
        super(String.format("[%s:seq=%d] %s", symbolId, sequence, message), cause);
        this.symbolId = symbolId;
        this.sequence = sequence;
    }

    public String getSymbolId() {
        return symbolId;
    }

    public long getSequence() {
        return sequence;
    }

    /**
     * 检查是否为可恢复的异常
     */
    public boolean isRecoverable() {
        Throwable cause = getCause();
        if (cause == null) {
            return false;
        }

        // 磁盘空间不足、网络异常等可能是临时的
        String message = cause.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            return lowerMessage.contains("no space left") ||
                   lowerMessage.contains("disk full") ||
                   lowerMessage.contains("connection") ||
                   lowerMessage.contains("timeout") ||
                   lowerMessage.contains("network");
        }

        return false;
    }
}