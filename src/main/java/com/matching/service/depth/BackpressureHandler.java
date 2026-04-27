package com.matching.service.depth;

import com.matching.model.DepthUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 背压处理器 - 4层数据保护机制
 */
@Slf4j
@Component
public class BackpressureHandler {

    @Value("${matching.depth.queue.capacity:10000}")
    private int queueCapacity;

    @Value("${matching.depth.backpressure.threshold:0.8}")
    private double backpressureThreshold;

    private final AtomicBoolean backpressureActive = new AtomicBoolean(false);
    private final AtomicLong totalDropped = new AtomicLong(0);

    /**
     * 处理背压情况 - 4层保护机制
     */
    public boolean handleBackpressure(DepthUpdate update,
                                      BlockingQueue<DepthUpdate> primaryQueue,
                                      BlockingQueue<DepthUpdate> emergencyQueue) {

        // 第1层：尝试紧急队列
        if (emergencyQueue.offer(update)) {
            if (!backpressureActive.getAndSet(true)) {
                log.warn("激活背压处理 - 主队列已满, 使用紧急队列");
            }
            return true;
        }

        // 第2层：阻塞等待 (短时间)
        try {
            if (primaryQueue.offer(update, 1, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("背压处理被中断");
            return false;
        }

        // 第3层：强制扩容 (临时)
        if (primaryQueue.size() < queueCapacity * 1.5) {
            try {
                if (primaryQueue.offer(update, 5, TimeUnit.MILLISECONDS)) {
                    log.warn("背压处理 - 强制扩容成功");
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 第4层：丢弃数据
        totalDropped.incrementAndGet();
        log.error("背压处理失败 - 丢弃深度更新: {}", update.getSymbol());
        return false;
    }

    /**
     * 检查是否可以退出背压模式
     */
    public boolean checkBackpressureRecovery(BlockingQueue<DepthUpdate> primaryQueue,
                                           BlockingQueue<DepthUpdate> emergencyQueue) {
        if (backpressureActive.get() &&
            primaryQueue.size() < queueCapacity * backpressureThreshold &&
            emergencyQueue.isEmpty()) {

            backpressureActive.set(false);
            log.info("退出背压模式");
            return true;
        }
        return false;
    }

    public boolean isBackpressureActive() {
        return backpressureActive.get();
    }

    public long getTotalDropped() {
        return totalDropped.get();
    }
}