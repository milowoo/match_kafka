package com.matching.validator;

import com.matching.config.symbol.SymbolConfig;
import com.matching.config.symbol.SymbolConfigService;
import com.matching.dto.*;
import com.matching.engine.MatchEngine;
import com.matching.service.OrderBookService;
import com.matching.service.OrderValidator;
import com.matching.service.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class OrderValidatorTest {

    @Mock private SymbolConfigService symbolConfigService;
    @Mock private OrderBookService orderBookService;
    @Mock private RateLimiter rateLimiter;
    @Mock private MatchEngine engine;

    private OrderValidator validator;
    private SymbolConfig symbolConfig;
    private static final String SYMBOL = "BTCUSDT";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        validator = new OrderValidator(symbolConfigService, orderBookService, rateLimiter);

        when(orderBookService.getEngine(SYMBOL)).thenReturn(engine);
        when(engine.getAccountOrderCount(anyLong())).thenReturn(0);

        symbolConfig = new SymbolConfig();
        symbolConfig.setSymbolId(SYMBOL);
        symbolConfig.setTradingEnabled(true);
        symbolConfig.setMinQuantity(new BigDecimal("0.001"));
        symbolConfig.setMaxQuantity(new BigDecimal("1000"));
        symbolConfig.setMinPrice(new BigDecimal("1.00"));
        symbolConfig.setMaxPrice(new BigDecimal("100000"));
        symbolConfig.setPricePrecision(2);
        symbolConfig.setQuantityPrecision(3);
        symbolConfig.setMinNotional(new BigDecimal("10"));
        symbolConfig.setPriceDeviationRate(new BigDecimal("0.2"));
        symbolConfig.setMaxOpenOrders(100);

        when(symbolConfigService.getConfig(SYMBOL)).thenReturn(symbolConfig);
        when(rateLimiter.check(anyLong(), anyString())).thenReturn(null);
    }

    @Test
    @DisplayName("1. 合法订单通过")
    void testValidOrderPasses() {
        assertNull(validator.validate(createValidOrderParam()));
    }

    @Test
    @DisplayName("2. Symbol未配置")
    void testSymbolNotConfigured() {
        when(symbolConfigService.getConfig(SYMBOL)).thenReturn(null);
        String result = validator.validate(createValidOrderParam());
        assertNotNull(result);
        assertTrue(result.contains("SYMBOL_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("3. 交易关闭")
    void testTradingDisabled() {
        symbolConfig.setTradingEnabled(false);
        String result = validator.validate(createValidOrderParam());
        assertNotNull(result);
        assertTrue(result.contains("TRADING_DISABLED"));
    }

    @Test
    @DisplayName("4. 空订单列表")
    void testEmptyOrderList() {
        PlaceOrderParam param = createValidOrderParam();
        param.setOrderList(null);
        String result = validator.validate(param);
        assertNotNull(result);
        assertTrue(result.contains("EMPTY_ORDER_LIST"));
    }

    @Test
    @DisplayName("5. 限流拦截")
    void testRateLimitBlocked() {
        when(rateLimiter.check(100L, SYMBOL)).thenReturn("Rate limit exceeded");
        String result = validator.validate(createValidOrderParam());
        assertNotNull(result);
        assertEquals("Rate limit exceeded", result);
    }

    @Test
    @DisplayName("6. 数量为零")
    void testZeroQuantity() {
        PlaceOrderParam param = createValidOrderParam();
        param.getOrderList().get(0).setDelegateCount(BigDecimal.ZERO);
        String result = validator.validate(param);
        assertNotNull(result);
        assertTrue(result.contains("QUANTITY_NOT_POSITIVE"));
    }

    @Test
    @DisplayName("7. 数量低于最小值")
    void testQuantityBelowMinimum() {
        PlaceOrderParam param = createValidOrderParam();
        param.getOrderList().get(0).setDelegateCount(new BigDecimal("0.0001"));
        String result = validator.validate(param);
        assertNotNull(result);
        assertTrue(result.contains("QUANTITY_BELOW_MIN"));
    }

    @Test
    @DisplayName("8. Market订单只校验数量")
    void testMarketOrderValidation() {
        PlaceOrderParam param = createValidOrderParam();
        param.getOrderList().get(0).setOrderType("MARKET");
        param.getOrderList().get(0).setDelegatePrice(null);
        assertNull(validator.validate(param));
    }

    private PlaceOrderParam createValidOrderParam() {
        PlaceOrderParam param = new PlaceOrderParam();
        param.setSymbolId(SYMBOL);
        param.setAccountId(100L);
        param.setUid(12345L); // 添加UID字段
        param.setBusinessLine("SPOT");
        param.setTokenId("TOKEN123");
        param.setRequestTime(System.currentTimeMillis());

        PlaceMiniOrderParam miniOrder = new PlaceMiniOrderParam();
        miniOrder.setOrderNo(1L);
        miniOrder.setDelegateType("BUY_IN_SINGLE_SIDE_MODE");
        miniOrder.setOrderType("LIMIT");
        miniOrder.setDelegateCount(new BigDecimal("1.000"));
        miniOrder.setDelegatePrice(new BigDecimal("50000.00"));
        miniOrder.setCreateTime(new Date());

        param.setOrderList(Arrays.asList(miniOrder));
        return param;
    }
}
