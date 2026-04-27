package com.matching.service.orderbook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LoadFailureHandler {

    private final OrderBookManager orderBookManager;

    public LoadFailureHandler(OrderBookManager orderBookManager) {
        this.orderBookManager = orderBookManager;
    }

    public void handleLoadFailure(String symbolId, String errorType, Exception e) {
        log.warn("Attempting fallback for symbolId: {} due to: {}", symbolId, errorType);
        try {
            orderBookManager.getEngine(symbolId);
            orderBookManager.markAsLoaded(symbolId);
            log.warn("Used fallback empty engine for symbolId: {}", symbolId);
        } catch (Exception fallbackError) {
            log.error("Fallback also failed for symbolId: {}", symbolId, fallbackError);
            throw new RuntimeException("Complete failure loading orderbook for " + symbolId +
                    " + errorType, fallbackError");
        }
    }
}