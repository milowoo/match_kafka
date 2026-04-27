package com.matching.controller;

import com.matching.config.symbol.SymbolConfig;
import com.matching.config.symbol.SymbolConfigService;
import com.matching.dto.ApiResult;
import com.matching.engine.MatchEngine;
import com.matching.service.UnifiedChronicleQueueEventLog;
import com.matching.service.EventLog;
import com.matching.service.HAService;
import com.matching.service.OrderBookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class OpsControllerTest {

    @Mock private OrderBookService orderBookService;
    @Mock private SymbolConfigService symbolConfigService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @Mock private MatchEngine mockEngine;
    @Mock private EventLog eventLog;
    @Mock private HAService haService;

    private OpsController controller;
    private static final String SYMBOL = "BTCUSDT";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new OpsController(orderBookService, symbolConfigService, redisTemplate, eventLog, haService);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    private SymbolConfig buildConfig(String symbolId, boolean tradingEnabled) {
        SymbolConfig c = new SymbolConfig();
        c.setSymbolId(symbolId);
        c.setTradingEnabled(tradingEnabled);
        return c;
    }

    @Test
    @DisplayName("1. 获取所有交易对状态")
    void testListSymbols() {
        Map<String, MatchEngine> engines = new HashMap<>();
        engines.put(SYMBOL, mockEngine);
        when(orderBookService.getAllEngines()).thenReturn(engines);

        com.matching.engine.PriceLevelBook buyBook = mock(com.matching.engine.PriceLevelBook.class);
        com.matching.engine.PriceLevelBook sellBook = mock(com.matching.engine.PriceLevelBook.class);
        when(mockEngine.orderCount()).thenReturn(100);
        when(mockEngine.getBuyBook()).thenReturn(buyBook);
        when(mockEngine.getSellBook()).thenReturn(sellBook);
        when(buyBook.size()).thenReturn(5);
        when(sellBook.size()).thenReturn(3);
        when(mockEngine.poolAvailable()).thenReturn(9980);
        when(symbolConfigService.getConfig(SYMBOL)).thenReturn(buildConfig(SYMBOL, true));

        ApiResult<List<OpsController.SymbolStatus>> result = controller.listSymbols();

        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals(1, result.getData().size());
        OpsController.SymbolStatus status = result.getData().get(0);
        assertEquals(SYMBOL, status.getSymbolId());
        assertEquals(100, status.getOrderCount());
        assertTrue(status.isTradingEnabled());
    }

    @Test
    @DisplayName("2. 获取单个交易对状态")
    void testGetSymbolStatus() {
        when(orderBookService.exists(SYMBOL)).thenReturn(true);
        when(orderBookService.getEngine(SYMBOL)).thenReturn(mockEngine);

        com.matching.engine.PriceLevelBook buyBook = mock(com.matching.engine.PriceLevelBook.class);
        com.matching.engine.PriceLevelBook sellBook = mock(com.matching.engine.PriceLevelBook.class);
        when(mockEngine.orderCount()).thenReturn(50);
        when(mockEngine.getBuyBook()).thenReturn(buyBook);
        when(mockEngine.getSellBook()).thenReturn(sellBook);
        when(buyBook.size()).thenReturn(3);
        when(sellBook.size()).thenReturn(2);
        when(mockEngine.poolAvailable()).thenReturn(9950);
        when(symbolConfigService.getConfig(SYMBOL)).thenReturn(buildConfig(SYMBOL, false));

        ApiResult<OpsController.SymbolStatus> result = controller.getSymbolStatus(SYMBOL);

        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals(SYMBOL, result.getData().getSymbolId());
        assertEquals(50, result.getData().getOrderCount());
        assertFalse(result.getData().isTradingEnabled());
    }

    @Test
    @DisplayName("3. 交易对不存在")
    void testGetSymbolStatusNotFound() {
        when(orderBookService.exists("UNKNOWN")).thenReturn(false);
        ApiResult<OpsController.SymbolStatus> result = controller.getSymbolStatus("UNKNOWN");
        assertTrue(result.getCode() != 0);
        assertTrue(result.getMessage().contains("Symbol not loaded"));
    }

    @Test
    @DisplayName("4. 启用交易")
    void testEnableTrading() {
        when(symbolConfigService.getConfig(SYMBOL)).thenReturn(buildConfig(SYMBOL, false));
        ApiResult<String> result = controller.setTradingEnabled(SYMBOL, true);
        assertEquals(0, result.getCode());
        assertTrue(result.getData().contains("enabled"));
        verify(hashOperations, times(1)).put(eq("matching:symbol:config"), eq(SYMBOL), anyString());
    }

    @Test
    @DisplayName("5. 禁用交易")
    void testDisableTrading() {
        when(symbolConfigService.getConfig(SYMBOL)).thenReturn(buildConfig(SYMBOL, true));
        ApiResult<String> result = controller.setTradingEnabled(SYMBOL, false);
        assertEquals(0, result.getCode());
        assertTrue(result.getData().contains("disabled"));
        verify(hashOperations, times(1)).put(eq("matching:symbol:config"), eq(SYMBOL), anyString());
    }

    @Test
    @DisplayName("6. 更新不存在的交易对")
    void testSetTradingEnabledNotFound() {
        when(symbolConfigService.getConfig("UNKNOWN")).thenReturn(null);
        ApiResult<String> result = controller.setTradingEnabled("UNKNOWN", true);
        assertTrue(result.getCode() != 0);
        assertTrue(result.getMessage().contains("Symbol not configured"));
    }

    @Test
    @DisplayName("7. 更新交易对配置")
    void testUpdateSymbolConfig() {
        SymbolConfig config = new SymbolConfig();
        config.setSymbolId(SYMBOL);
        config.setTradingEnabled(true);
        config.setMinQuantity(new BigDecimal("0.001"));
        config.setMaxQuantity(new BigDecimal("1000"));

        ApiResult<String> result = controller.updateSymbolConfig(config);
        assertEquals(0, result.getCode());
        assertTrue(result.getData().contains("Config updated"));
        verify(hashOperations, times(1)).put(eq("matching:symbol:config"), eq(SYMBOL), anyString());
    }

    @Test
    @DisplayName("8. 更新配置缺少symbolId")
    void testUpdateSymbolConfigMissingSymbolId() {
        SymbolConfig config = new SymbolConfig();
        config.setTradingEnabled(true);
        ApiResult<String> result = controller.updateSymbolConfig(config);
        assertTrue(result.getCode() != 0);
        assertTrue(result.getMessage().contains("symbolId is required"));
    }

    @Test
    @DisplayName("9. 获取实例信息")
    void testInstanceInfo() throws Exception {
        UnifiedChronicleQueueEventLog mockCqEventLog = mock(UnifiedChronicleQueueEventLog.class);
        when(mockCqEventLog.getRole(null)).thenReturn("PRIMARY");

        OpsController testController = new OpsController(
                orderBookService, symbolConfigService, redisTemplate, mockCqEventLog, haService);

        java.lang.reflect.Field portField = OpsController.class.getDeclaredField("serverPort");
        portField.setAccessible(true);
        portField.set(testController, 8080);

        java.lang.reflect.Field idField = OpsController.class.getDeclaredField("instanceId");
        idField.setAccessible(true);
        idField.set(testController, "node-1");

        ApiResult<OpsController.InstanceInfo> result = testController.instanceInfo();

        assertEquals(0, result.getCode());
        assertNotNull(result.getData());
        assertEquals(8080, result.getData().getPort());
        assertEquals("node-1", result.getData().getInstanceId());
        assertTrue(result.getData().isActive());
        assertEquals("ACTIVE", result.getData().getState());
    }
}
