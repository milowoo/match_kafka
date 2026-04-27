package com.matching.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matching.config.symbol.SymbolConfig;
import com.matching.config.symbol.SymbolConfigService;
import com.matching.dto.ApiResult;
import com.matching.engine.MatchEngine;
import com.matching.service.OrderBookService;
import com.matching.service.EventLog;
import com.matching.service.UnifiedChronicleQueueEventLog;
import com.matching.service.HAService;
import com.matching.util.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/ops")
public class OpsController {

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${matching.ha.instance-id:node-1}")
    private String instanceId;

    private final OrderBookService orderBookService;
    private final SymbolConfigService symbolConfigService;
    private final StringRedisTemplate redisTemplate;
    private final EventLog eventLog;
    private final HAService haService;
    private final ObjectMapper objectMapper = JsonUtil.jackson();

    public OpsController(OrderBookService orderBookService,
                          SymbolConfigService symbolConfigService,
                          StringRedisTemplate redisTemplate,
                          EventLog eventLog,
                          HAService haService) {
        this.orderBookService = orderBookService;
        this.symbolConfigService = symbolConfigService;
        this.redisTemplate = redisTemplate;
        this.eventLog = eventLog;
        this.haService = haService;
    }

    @GetMapping("/symbols")
    public ApiResult<List<SymbolStatus>> listSymbols() {
        List<SymbolStatus> list = new ArrayList<>();
        // Use reflection-free approach: iterate loaded symbols via exists check
        // OrderBookService doesn't expose loadedSymbols directly, so we go through engines
        for (Map.Entry<String, MatchEngine> entry : getEngines().entrySet()) {
            MatchEngine engine = entry.getValue();
            SymbolConfig config = symbolConfigService.getConfig(entry.getKey());
            list.add(new SymbolStatus(
                    entry.getKey(),
                    engine.orderCount(),
                    engine.getBuyBook().size(),
                    engine.getSellBook().size(),
                    engine.poolAvailable(),
                    config != null && config.isTradingEnabled()));
        }
        return ApiResult.ok(list);
    }

    @GetMapping("/symbol/{symbolId}")
    public ApiResult<SymbolStatus> getSymbolStatus(@PathVariable String symbolId) {
        if (!orderBookService.exists(symbolId)) {
            return ApiResult.error("Symbol not loaded: " + symbolId);
        }
        MatchEngine engine = orderBookService.getEngine(symbolId);
        SymbolConfig config = symbolConfigService.getConfig(symbolId);
        return ApiResult.ok(new SymbolStatus(
                symbolId,
                engine.orderCount(),
                engine.getBuyBook().size(),
                engine.getSellBook().size(),
                engine.poolAvailable(),
                config != null && config.isTradingEnabled()));
    }

    @PostMapping("/symbol/{symbolId}/trading")
    public ApiResult<String> setTradingEnabled(@PathVariable String symbolId,
                                               @RequestParam boolean enabled) {
        try {
            SymbolConfig config = symbolConfigService.getConfig(symbolId);
            if (config == null) {
                return ApiResult.error("Symbol not configured: " + symbolId);
            }
            config.setTradingEnabled(enabled);
            // Persist to Redis
            redisTemplate.opsForHash().put("matching:symbol:config", symbolId,
                    objectMapper.writeValueAsString(config));
            return ApiResult.ok("Trading " + (enabled ? "enabled" : "disabled") + " for " + symbolId);
        } catch (Exception e) {
            return ApiResult.error("Failed to update: " + e.getMessage());
        }
    }

    @PostMapping("/symbol/update")
    public ApiResult<String> updateSymbolConfig(@RequestBody SymbolConfig config) {
        if (config.getSymbolId() == null) {
            return ApiResult.error("symbolId is required");
        }
        try {
            redisTemplate.opsForHash().put("matching:symbol:config", config.getSymbolId(),
                    objectMapper.writeValueAsString(config));
            return ApiResult.ok("Config updated for " + config.getSymbolId());
        } catch (Exception e) {
            return ApiResult.error("Failed to update: " + e.getMessage());
        }
    }

    @PostMapping("/symbol/{symbolId}/import-orderbook")
    public ApiResult<String> importOrderBook(@PathVariable String symbolId) {
        try {
            log.info("Received import orderbook request for symbol: {}", symbolId);
            
            // 检查是否在PRIMARY角色
            if (!haService.isActive()) {
                return ApiResult.error("Cannot import orderbook in STANDBY mode");
            }
            
            // 检查订单簿是否已存在
            if (orderBookService.exists(symbolId)) {
                log.warn("OrderBook already exists for symbolId: {}, skipping import", symbolId);
                return ApiResult.ok("OrderBook for symbol " + symbolId + " already exists");
            }
            
            // 从EventLog导入订单簿
            orderBookService.loadFromEventLog(symbolId, true);
            
            log.info("OrderBook imported successfully for symbolId: {}", symbolId);
            return ApiResult.ok("OrderBook for symbol " + symbolId + " imported successfully");
        } catch (Exception e) {
            log.error("Failed to import orderbook for symbol: {}", symbolId, e);
            return ApiResult.error("Failed to import orderbook: " + e.getMessage());
        }
    }

    // Helper: access engines map via OrderBookService
    private Map<String, MatchEngine> getEngines() {return orderBookService.getAllEngines();}

    // ===================== Instance Info (for external callers)=====================
    // Returns this instance's IP, port, and Active/Standby state. Business callers use this to discover which
    // instance is Active. GET /ops/instance/info
    @GetMapping("/instance/info")
    public ApiResult<InstanceInfo> instanceInfo() {
        boolean isActive = false;
        String state = "STANDBY";

        try {
            if (eventLog instanceof UnifiedChronicleQueueEventLog unifiedEventLog) {
                // 统一架构：按实例级别判断
                isActive = "PRIMARY".equals(unifiedEventLog.getRole(null));
                state = isActive ? "ACTIVE" : "STANDBY";
            } else {
                // 文件模式默认为活跃状态
                isActive = true;
                state = "ACTIVE";
            }
        } catch (Exception e) {
            log.warn("Failed to get HA status, defaulting to STANDBY", e);
            isActive = false;
            state = "STANDBY";
        }

        return ApiResult.ok(new InstanceInfo(
                getLocalIp(),
                serverPort,
                instanceId,
                isActive,
                state));
    }

    private String getLocalIp() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Data
    @AllArgsConstructor
    static class InstanceInfo {
        private String ip;
        private int port;
        private String instanceId;
        private boolean active;
        private String state;
    }

    @Data
    @AllArgsConstructor
    static class SymbolStatus {
        private String symbolId;
        private int orderCount;
        private int buyLevels;
        private int sellLevels;
        private int poolAvailable;
        private boolean tradingEnabled;
    }
}