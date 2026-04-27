package com.matching.config.symbol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matching.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class SymbolConfigService {
    private static final Logger log = LoggerFactory.getLogger(SymbolConfigService.class);

    private static final String CONFIG_KEY = "matching:symbol:config";

    private final Map<String, SymbolConfig> configCache = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> lastTradePrices = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = JsonUtil.jackson();

    public SymbolConfigService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        refreshConfigs();
        log.info("Loaded {} symbol configs from Redis", configCache.size());
    }

    public SymbolConfig getConfig(String symbolId) {
        return configCache.get(symbolId);
    }

    /**
     * Load from Redis and cache. Called on refresh or first access. Returns null if not configured - caller must handle.
     */
    public SymbolConfig loadAndCache(String symbolId) {
        SymbolConfig config = loadConfig(symbolId);
        if (config != null) {
            configCache.put(symbolId, config);
        }
        return config;
    }

    public void updateLastTradePrice(String symbolId, BigDecimal price) {
        lastTradePrices.put(symbolId, price);
    }

    public BigDecimal getLastTradePrice(String symbolId) {
        return lastTradePrices.get(symbolId);
    }

    /**
     * 获取所有已配置且启用交易的交易对ID列表
     */
    public List<String> getActiveSymbolIds() {
        return configCache.values().stream()
                .filter(SymbolConfig::isTradingEnabled)
                .map(SymbolConfig::getSymbolId)
                .collect(Collectors.toList());
    }

    @Scheduled(fixedDelay = 10000)
    public void refreshConfigs() {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(CONFIG_KEY);
            int loadedCount = 0;
            int invalidCount = 0;

            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                try {
                    SymbolConfig config = objectMapper.readValue(entry.getValue().toString(), SymbolConfig.class);

                    // 校验配置完整性
                    String validationError = validateConfig(config);
                    if (validationError != null) {
                        log.error("Invalid symbol config for {}: {}", entry.getKey(), validationError);
                        invalidCount++;
                        continue; // 不放入缓存
                    }

                    configCache.put(config.getSymbolId(), config);
                    loadedCount++;
                } catch (Exception e) {
                    log.error("Failed to parse symbol config: {}", entry.getKey(), e);
                    invalidCount++;
                }
            }

            if (loadedCount > 0 || invalidCount > 0) {
                log.info("Symbol config refresh completed - loaded: {}, invalid: {}, total cached: {}",
                        loadedCount, invalidCount, configCache.size());
            }
        } catch (Exception e) {
            log.error("Failed to refresh symbol configs from Redis", e);
        }
    }

    private SymbolConfig loadConfig(String symbolId) {
        try {
            Object value = redisTemplate.opsForHash().get(CONFIG_KEY, symbolId);
            if (value != null) {
                SymbolConfig config = objectMapper.readValue(value.toString(), SymbolConfig.class);

                // 校验配置完整性
                String validationError = validateConfig(config);
                if (validationError != null) {
                    log.error("Invalid symbol config for {}: {}", symbolId, validationError);
                    return null; // 返回null，不缓存无效配置
                }

                return config;
            }
        } catch (Exception e) {
            log.error("Failed to load symbol config for: {}", symbolId, e);
        }
        return null;
    }

    /**
     * 校验交易对配置的完整性和合理性
     * @param config - 交易对配置
     * @return null表示校验通过，非null表示错误信息
     */
    private String validateConfig(SymbolConfig config) {
        if (config == null) {
            return "Config is null";
        }

        if (config.getSymbolId() == null || config.getSymbolId().trim().isEmpty()) {
            return "SymbolId is null or empty";
        }

        // 校验必要的数值字段
        if (config.getMinQuantity() == null) {
            return "MinQuantity is null";
        }
        if (config.getMaxQuantity() == null) {
            return "MaxQuantity is null";
        }
        if (config.getMinPrice() == null) {
            return "MinPrice is null";
        }
        if (config.getMaxPrice() == null) {
            return "MaxPrice is null";
        }

        // 校验数值合理性
        if (config.getMinQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return "MinQuantity must be positive: " + config.getMinQuantity();
        }
        if (config.getMaxQuantity().compareTo(config.getMinQuantity()) < 0) {
            return "MaxQuantity must be >= MinQuantity: max=" + config.getMaxQuantity() + ", min=" + config.getMinQuantity();
        }
        if (config.getMinPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return "MinPrice must be positive: " + config.getMinPrice();
        }
        if (config.getMaxPrice().compareTo(config.getMinPrice()) < 0) {
            return "MaxPrice must be >= MinPrice: max=" + config.getMaxPrice() + ", min=" + config.getMinPrice();
        }

        // 校验精度设置
        if (config.getPricePrecision() < 0 || config.getPricePrecision() > 18) {
            return "PricePrecision must be between 0 and 18: " + config.getPricePrecision();
        }
        if (config.getQuantityPrecision() < 0 || config.getQuantityPrecision() > 18) {
            return "QuantityPrecision must be between 0 and 18: " + config.getQuantityPrecision();
        }

        // 校验可选字段
        if (config.getMinNotional() != null && config.getMinNotional().compareTo(BigDecimal.ZERO) <= 0) {
            return "MinNotional must be positive when set: " + config.getMinNotional();
        }
        if (config.getPriceDeviationRate() != null) {
            if (config.getPriceDeviationRate().compareTo(BigDecimal.ZERO) <= 0 ||
                    config.getPriceDeviationRate().compareTo(BigDecimal.ONE) > 0) {
                return "PriceDeviationRate must be between 0 and 1 when set: " + config.getPriceDeviationRate();
            }
        }
        if (config.getMaxOpenOrders() < 0) {
            return "MaxOpenOrders must be non-negative: " + config.getMaxOpenOrders();
        }

        // 校验穿仓保护配置
        if (config.getMaxPriceLevels() < 0) {
            return "MaxPriceLevels must be non-negative: " + config.getMaxPriceLevels();
        }
        if (config.getMaxSlippageRate() != null) {
            if (config.getMaxSlippageRate().compareTo(BigDecimal.ZERO) <= 0 ||
                    config.getMaxSlippageRate().compareTo(BigDecimal.ONE) > 0) {
                return "MaxSlippageRate must be between 0 and 1 when set: " + config.getMaxSlippageRate();
            }
        }

        return null; // 校验通过
    }
}