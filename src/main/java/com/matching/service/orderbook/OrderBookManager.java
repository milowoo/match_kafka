package com.matching.service.orderbook;

import com.matching.engine.MatchEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class OrderBookManager {

    private final Map<String, MatchEngine> engines = new ConcurrentHashMap<>();
    private final Set<String> loadedSymbols = ConcurrentHashMap.newKeySet();

    public boolean exists(String symbolId) {
        return loadedSymbols.contains(symbolId);
    }

    public MatchEngine getEngine(String symbolId) {
        return engines.computeIfAbsent(symbolId, k -> new MatchEngine(symbolId));
    }

    public Map<String, MatchEngine> getAllEngines() {
        return Collections.unmodifiableMap(engines);
    }

    public void markAsLoaded(String symbolId) {
        loadedSymbols.add(symbolId);
    }

    public void removeEngine(String symbolId) {
        engines.remove(symbolId);
        loadedSymbols.remove(symbolId);
        log.info("Removed engine for symbolId: {}", symbolId);
    }

    public void clearSymbol(String symbolId) {
        removeEngine(symbolId);
    }
}