package com.matching.service.chronicle;

import com.matching.service.EventLog;
import com.matching.service.OrderBookService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class ChronicleQueueHAManager {

    private final ChronicleQueueManager queueManager;
    private final ChronicleQueueReader reader;
    private final String instanceId;
    private final boolean transactionEnabled;
    private final AtomicLong globalSeq;
    private final AtomicLong committedSeq;
    private OrderBookService orderBookService;

    public ChronicleQueueHAManager(ChronicleQueueManager queueManager, ChronicleQueueReader reader,
                                   String instanceId, boolean transactionEnabled,
                                   AtomicLong globalSeq, AtomicLong committedSeq) {
        this.queueManager = queueManager;
        this.reader = reader;
        this.instanceId = instanceId;
        this.transactionEnabled = transactionEnabled;
        this.globalSeq = globalSeq;
        this.committedSeq = committedSeq;
    }

    public void setOrderBookService(OrderBookService orderBookService) {
        this.orderBookService = orderBookService;
    }

    public synchronized void switchToPrimary() {
        long startTime = System.currentTimeMillis();
        log.info("Instance {} switching to PRIMARY...", instanceId);

        try {
            if (!performConsistencyCheck()) {
                throw new RuntimeException("Consistency check failed, cannot switch to PRIMARY");
            }

            if (!queueManager.switchToPrimary()) {
                throw new RuntimeException("Failed to acquire primary role");
            }

            recoverFromQueue();

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Instance {} switched to PRIMARY in {}ms", instanceId, elapsed);
        } catch (Exception e) {
            log.error("Failed to switch instance {} to PRIMARY", instanceId, e);
            throw new RuntimeException("Switch to primary failed", e);
        }
    }

    public synchronized void switchToStandby() {
        log.info("Instance {} switching to STANDBY...", instanceId);
        try {
            if (!queueManager.switchToStandby()) {
                throw new RuntimeException("Failed to switch to standby role");
            }
            log.info("Instance {} switched to STANDBY", instanceId);
        } catch (Exception e) {
            log.error("Failed to switch instance {} to STANDBY", instanceId, e);
            throw new RuntimeException("Switch to standby failed", e);
        }
    }

    private boolean performConsistencyCheck() {
        try {
            long lastWriteTime = reader.getLastWriteTimestamp();
            long currentTime = System.currentTimeMillis();

            if (currentTime - lastWriteTime < 30000) {
                log.warn("[ConsistencyCheck] Recent write activity detected, last write: {}ms ago",
                        currentTime - lastWriteTime);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }

                lastWriteTime = reader.getLastWriteTimestamp();
                currentTime = System.currentTimeMillis();

                if (currentTime - lastWriteTime < 10000) {
                    log.error("[ConsistencyCheck] Active primary instance detected, cannot switch");
                    return false;
                }
            }

            // 从本地EventLog获取最大seq(这是通过Kafka复制从主实例同步过来的)
            long queueSeq = reader.getMaxSequenceFromQueue();
            long currentSeq = globalSeq.get();

            // 如果本地EventLog中的seq大于当前globalSeq,说明有复制的EventLog未同步到globalSeq
            if (queueSeq > currentSeq) {
                log.info("[ConsistencyCheck] Queue has newer data from replication, updating globalSeq: {} -> {}",
                        currentSeq, queueSeq);
                globalSeq.set(queueSeq);
                committedSeq.set(queueSeq);
                log.info("[ConsistencyCheck] Updated both globalSeq and committedSeq to: {}", queueSeq);
            } else if (queueSeq < currentSeq) {
                log.warn("[ConsistencyCheck] Queue seq {} is less than current globalSeq {}, using current globalSeq",
                        queueSeq, currentSeq);
            } else {
                log.info("[ConsistencyCheck] Queue seq and globalSeq are consistent at: {}", queueSeq);
            }

            log.info("[ConsistencyCheck] Consistency check passed, safe to switch to PRIMARY");
            return true;
        } catch (Exception e) {
            log.error("[ConsistencyCheck] Consistency check failed", e);
            return false;
        }
    }

    private void recoverFromQueue() {
        log.info("Starting recovery from Chronicle Queue...");

        // 保存原始状态用于回滚
        long originalGlobalSeq = globalSeq.get();
        long originalCommittedSeq = committedSeq.get();

        // 首先从本地EventLog获取最大seq(这是通过Kafka复制从主实例同步过来的)
        long queueMaxSeq = reader.getMaxSequenceFromQueue();
        log.info("[Recovery] Max seq from local EventLog: {}", queueMaxSeq);

        // 如果本地EventLog有数据,使用EventLog中的最大seq作为起点
        // 这样可以确保从实例的seq与主实例保持一致
        if (queueMaxSeq > 0) {
            globalSeq.set(queueMaxSeq);
            committedSeq.set(queueMaxSeq);
            log.info("[Recovery] Initialized globalSeq and committedSeq from EventLog: {}", queueMaxSeq);
        } else {
            // 如果本地EventLog没有数据,从0开始
            globalSeq.set(0);
            committedSeq.set(0);
            log.info("[Recovery] No data in EventLog, starting from seq 0");
        }

        // 记录恢复状态
        List<String> recoveredSymbols = new ArrayList<>();
        List<EventLog.Event> processedEvents = new ArrayList<>();
        int totalEvents = 0;
        int failedEvents = 0;

        try {
            // 检查OrderBookService可用性
            if (orderBookService == null) {
                throw new IllegalStateException("OrderBookService not available for recovery");
            }

            // 读取所有数据
            List<EventLog.Event> events = reader.readEvents(null, 0);
            totalEvents = events.size();

            if (events.isEmpty()) {
                log.info("No events to recover from Chronicle Queue");
                return;
            }

            log.info("Found {} events to recover", totalEvents);

            long maxSeq = 0;
            int recoveredCount = 0;

            for (EventLog.Event event : events) {
                try {
                    // 验证事件完整性
                    if (event == null || event.getSymbolId() == null || event.getSeq() <= 0) {
                        log.warn("Invalid event detected, skipping: {}", event);
                        continue;
                    }

                    // 恢复事件
                    orderBookService.recoverFromEvent(event);

                    // 记录成功恢复的事件和symbol
                    processedEvents.add(event);
                    if (!recoveredSymbols.contains(event.getSymbolId())) {
                        recoveredSymbols.add(event.getSymbolId());
                    }

                    maxSeq = Math.max(maxSeq, event.getSeq());
                    recoveredCount++;

                    log.debug("Recovered event seq: {} for symbol: {}", event.getSeq(), event.getSymbolId());
                } catch (Exception e) {
                    failedEvents++;
                    log.error("Failed to recover event seq: {} for symbol: {}",
                            event.getSeq(), event.getSymbolId(), e);

                    // 检查是否为关键错误
                    if (isCriticalRecoveryError(e)) {
                        log.error("Critical recovery error detected, initiating rollback");
                        throw new RuntimeException("Critical recovery failure at seq: " + event.getSeq(), e);
                    }

                    // 检查失败率是否过高
                    double failureRate = (double) failedEvents / (recoveredCount + failedEvents);
                    if (failureRate > 0.1 && (recoveredCount + failedEvents) > 10) { // 失败率超过10%
                        log.error("Recovery failure rate too high: {}%, initiating rollback", failureRate * 100);
                        throw new RuntimeException("Recovery failure rate exceeded threshold: " + failureRate);
                    }
                }
            }

            // 更新序列号(使用恢复过程中遇到的最大seq)
            // 这样可以确保globalSeq与实际恢复的EventLog一致
            if (maxSeq > queueMaxSeq) {
                log.warn("[Recovery] Found maxSeq {} greater than queueMaxSeq {}, updating to maxSeq",
                        maxSeq, queueMaxSeq);
                globalSeq.set(maxSeq);
                committedSeq.set(maxSeq);
            } else {
                log.info("[Recovery] Final seq: {}, queueMaxSeq: {}, recoveredCount: {}",
                        maxSeq, queueMaxSeq, recoveredCount);
            }

            log.info("Recovery completed successfully. {} events recovered, {} failed, maxSeq: {}, symbols: {}",
                    recoveredCount, failedEvents, maxSeq, recoveredSymbols.size());
        } catch (Exception e) {
            log.error("Recovery failed, initiating rollback. Processed {} events, failed on {} symbols",
                    processedEvents.size(), recoveredSymbols.size(), e);

            // 执行回滚
            performRecoveryRollback(recoveredSymbols, originalGlobalSeq, originalCommittedSeq);

            // 重新抛出异常
            throw new RuntimeException("Recovery failed and rolled back", e);
        }
    }

    /**
     * 判断是否为关键恢复错误
     */
    private boolean isCriticalRecoveryError(Throwable e) {
        // 内存不足
        if (e instanceof OutOfMemoryError) {
            return true;
        }

        // 检查是否为内存相关错误
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof OutOfMemoryError) {
                return true;
            }
            cause = cause.getCause();
        }

        // 数据损坏相关错误
        String message = e.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            return lowerMessage.contains("corrupt") ||
                    lowerMessage.contains("invalid data") ||
                    lowerMessage.contains("data integrity") ||
                    lowerMessage.contains("sequence mismatch");
        }

        return false;
    }

    /**
     * 执行恢复回滚
     */
    private void performRecoveryRollback(List<String> recoveredSymbols, long originalGlobalSeq, long originalCommittedSeq) {
        log.warn("Starting recovery rollback for {} symbols", recoveredSymbols.size());

        try {
            // 清理已恢复的symbol数据
            for (String symbolId : recoveredSymbols) {
                try {
                    if (orderBookService != null) {
                        orderBookService.clearSymbol(symbolId);
                        log.debug("Rolled back symbol: {}", symbolId);
                    }
                } catch (Exception e) {
                    log.warn("Failed to rollback symbol: {}", symbolId, e);
                }
            }

            // 恢复原始序列号
            globalSeq.set(originalGlobalSeq);
            committedSeq.set(originalCommittedSeq);

            log.info("Recovery rollback completed. Restored globalSeq: {}, committedSeq: {}",
                    originalGlobalSeq, originalCommittedSeq);
        } catch (Exception e) {
            log.error("Failed to complete recovery rollback", e);
            // 即使回滚失败，也要恢复序列号
            globalSeq.set(originalGlobalSeq);
            committedSeq.set(originalCommittedSeq);
        }
    }

    public void recoverGlobalSeq() {
        // 从本地EventLog获取最大seq(这是通过Kafka复制从主实例同步过来的)
        long maxSeq = reader.getMaxSequenceFromQueue();
        
        // 如果本地EventLog有数据,使用EventLog中的最大seq
        if (maxSeq > 0) {
            long currentSeq = globalSeq.get();
            globalSeq.set(maxSeq);
            committedSeq.set(maxSeq);
            
            if (maxSeq > currentSeq) {
                log.info("[RecoverGlobalSeq] Updated globalSeq: {} -> {}, committedSeq: {}",
                        currentSeq, maxSeq, maxSeq);
            } else {
                log.info("[RecoverGlobalSeq] globalSeq already up-to-date: {}", maxSeq);
            }
        } else {
            log.info("[RecoverGlobalSeq] No data in EventLog, keeping current globalSeq: {}",
                    globalSeq.get());
        }
    }
}