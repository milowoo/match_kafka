package com.matching.config.symbol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SymbolConfigValidationTest {

    private SymbolConfigService symbolConfigService;
    private StringRedisTemplate redisTemplate;
    private HashOperations<String, Object, Object> hashOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        symbolConfigService = new SymbolConfigService(redisTemplate);
    }

    @Test
    void testValidConfig() {
        Map<Object, Object> entries = new HashMap<>();
        entries.put("BTCUSDT", "{\"symbolId\":\"BTCUSDT\",\"pricePrecision\":2,\"quantityPrecision\":8," +
                "\"minQuantity\":0.001,\"maxQuantity\":1000,\"minPrice\":0.01,\"maxPrice\":100000," +
                "\"minNotional\":10,\"priceDeviationRate\":0.1,\"maxOpenOrders\":100,\"tradingEnabled\":true}");

        when(hashOperations.entries("matching:symbol:config")).thenReturn(entries);

        symbolConfigService.refreshConfigs();

        SymbolConfig loadedConfig = symbolConfigService.getConfig("BTCUSDT");
        assertNotNull(loadedConfig);
        assertEquals("BTCUSDT", loadedConfig.getSymbolId());
    }

    @Test
    void testInvalidConfigWithNullFields() {
        Map<Object, Object> entries = new HashMap<>();
        entries.put("INVALID", "{\"symbolId\":\"INVALID\",\"pricePrecision\":2,\"quantityPrecision\":8," +
                "\"minQuantity\":null,\"maxQuantity\":1000,\"minPrice\":0.01,\"maxPrice\":100000}");

        when(hashOperations.entries("matching:symbol:config")).thenReturn(entries);

        symbolConfigService.refreshConfigs();

        assertNull(symbolConfigService.getConfig("INVALID"));
    }

    @Test
    void testInvalidConfigWithBadValues() {
        Map<Object, Object> entries = new HashMap<>();
        entries.put("BAD_VALUES", "{\"symbolId\":\"BAD_VALUES\",\"pricePrecision\":2,\"quantityPrecision\":8," +
                "\"minQuantity\":1000,\"maxQuantity\":0.001,\"minPrice\":100,\"maxPrice\":0.01}");

        when(hashOperations.entries("matching:symbol:config")).thenReturn(entries);

        symbolConfigService.refreshConfigs();

        assertNull(symbolConfigService.getConfig("BAD_VALUES"));
    }

    @Test
    void testLoadAndCacheWithValidation() {
        when(hashOperations.get("matching:symbol:config", "INVALID_SYMBOL"))
                .thenReturn("{\"symbolId\":\"INVALID_SYMBOL\",\"minQuantity\":null}");

        SymbolConfig config = symbolConfigService.loadAndCache("INVALID_SYMBOL");

        assertNull(config);
        assertNull(symbolConfigService.getConfig("INVALID_SYMBOL"));
    }
}
