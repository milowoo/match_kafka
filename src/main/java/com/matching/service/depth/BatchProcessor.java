package com.matching.service.depth;

import com.matching.model.DepthUpdate;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 批处理管理器 - 负责批量处理和发送
 */
@Slf4j
@Component
public class BatchProcessor {

    @Value("${matching.depth.batch.size:100}")
    private int batchSize;

    @Value("${matching.depth.batch.timeout:10}")
    private int batchTimeoutMs;

    private final FastList<DepthUpdate> batchBuffer = new FastList<>();
    private final ReentrantLock batchLock = new ReentrantLock();
    private volatile long lastBatchTime = System.currentTimeMillis();
    private final AtomicLong batchCount = new AtomicLong(0);

    /**
     * 处理单个更新 - 加入批处理缓冲区
     */
    public boolean processSingleUpdate(DepthUpdate update, ExecutorService publisherExecutor,
                                     DepthSender depthSender) {
        batchLock.lock();
        try {
            batchBuffer.add(update);

            // 检查是否需要立即发送
            if (batchBuffer.size() >= batchSize ||
                System.currentTimeMillis() - lastBatchTime > batchTimeoutMs) {
                return flushBatchInternal(publisherExecutor, depthSender);
            }
            return true;
        } finally {
            batchLock.unlock();
        }
    }

    /**
     * 强制刷新批处理缓冲区
     */
    public boolean flushBatch(ExecutorService publisherExecutor, DepthSender depthSender) {
        batchLock.lock();
        try {
            if (!batchBuffer.isEmpty()) {
                return flushBatchInternal(publisherExecutor, depthSender);
            }
            return true;
        } finally {
            batchLock.unlock();
        }
    }

    /**
     * 内部批处理刷新
     */
    private boolean flushBatchInternal(ExecutorService publisherExecutor, DepthSender depthSender) {
        if (batchBuffer.isEmpty()) {
            return true;
        }

        FastList<DepthUpdate> batch = new FastList<>(batchBuffer);
        batchBuffer.clear();
        lastBatchTime = System.currentTimeMillis();

        // 异步发送批次
        publisherExecutor.submit(() -> sendBatch(batch, depthSender));
        batchCount.incrementAndGet();
        return true;
    }

    /**
     * 发送批次数据
     */
    private void sendBatch(FastList<DepthUpdate> batch, DepthSender depthSender) {
        for (DepthUpdate update : batch) {
            try {
                depthSender.sendSingleUpdate(update);
            } catch (Exception e) {
                log.error("发送深度更新失败: {}", update.getSymbol(), e);
            }
        }
    }

    /**
     * 检查是否需要刷新
     */
    public boolean shouldFlush() {
        return !batchBuffer.isEmpty() &&
               (System.currentTimeMillis() - lastBatchTime >= batchTimeoutMs);
    }

    public long getBatchCount() {
        return batchCount.get();
    }

    public int getCurrentBatchSize() {
        return batchBuffer.size();
    }
}