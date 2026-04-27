package com.matching.service;

import com.matching.command.MatchExecutor;
import com.matching.command.MatchResultBuilder;
import com.matching.command.PlaceOrderCommand;
import com.matching.command.TakerOrderFactory;
import com.matching.config.symbol.SymbolConfig;
import com.matching.config.symbol.SymbolConfigService;
import com.matching.dto.*;
import com.matching.engine.CompactOrderBookEntry;
import com.matching.engine.MatchEngine;
import com.matching.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IcebergOrderTest {

    @Mock private OrderBookService orderBookService;
    @Mock private OrderValidator orderValidator;
    @Mock private SymbolConfigService symbolConfigService;
    @Mock private RateLimiter rateLimiter;
    @Mock private SnowflakeIdGenerator snowflakeIdGenerator;
    @Mock private com.matching.service.HAService haService;

    private PlaceOrderCommand placeOrderCommand;
    private MatchEngine matchEngine;

    @BeforeEach
    void setUp() {
        MatchResultBuilder resultBuilder = new MatchResultBuilder();
        TakerOrderFactory takerOrderFactory = new TakerOrderFactory();
        MatchExecutor matchExecutor = new MatchExecutor(symbolConfigService, orderBookService, snowflakeIdGenerator, resultBuilder);
        placeOrderCommand = new PlaceOrderCommand(orderBookService, orderValidator, takerOrderFactory, matchExecutor, resultBuilder, haService);
        matchEngine = new MatchEngine("BTC-USDT", 10000);
        placeOrderCommand.setEngine(matchEngine);

        when(orderBookService.exists(any())).thenReturn(true);
        when(orderValidator.validate(any())).thenReturn(null);
        when(snowflakeIdGenerator.nextId()).thenReturn(1L, 2L, 3L, 4L, 5L);
        when(haService.isActive()).thenReturn(true);
    }

    @Test
    void testIcebergOrderCreation() {
        PlaceMiniOrderParam miniOrder = PlaceMiniOrderParam.builder()
                .orderNo(1001L)
                .delegateType("BUY")
                .orderType("LIMIT")
                .delegatePrice(new BigDecimal("50000"))
                .delegateCount(new BigDecimal("10"))
                .isIceberg(true)
                .icebergTotalQuantity(new BigDecimal("10"))
                .icebergDisplayQuantity(new BigDecimal("2"))
                .icebergRefreshQuantity(new BigDecimal("2"))
                .createTime(new Date())
                .vip(false)
                .build();

        PlaceOrderParam orderParam = new PlaceOrderParam();
        orderParam.setSymbolId("BTC-USDT");
        orderParam.setAccountId(12345L);
        orderParam.setUid(12345L); // 添加UID字段
        orderParam.setRequestTime(System.currentTimeMillis());
        orderParam.setOrderList(Collections.singletonList(miniOrder));

        CommandResult result = placeOrderCommand.execute(orderParam);

        assertNotNull(result);
        assertNotNull(result.getMatchResult());
        assertEquals(1, result.getMatchResult().getCreateOrders().size());
        assertEquals(1001L, result.getMatchResult().getCreateOrders().get(0).getId());

        CompactOrderBookEntry entry = matchEngine.getOrder(1001L);
        assertNotNull(entry);
        assertTrue(entry.isIceberg);
        assertEquals(CompactOrderBookEntry.toLong(new BigDecimal("10")), entry.icebergTotalQuantity);
        assertEquals(CompactOrderBookEntry.toLong(new BigDecimal("2")), entry.icebergDisplayQuantity);
        assertEquals(CompactOrderBookEntry.toLong(new BigDecimal("2")), entry.remainingQty);
        assertEquals(CompactOrderBookEntry.toLong(new BigDecimal("8")), entry.icebergHiddenQuantity);
    }

    @Test
    void testIcebergOrderMatching() {
        testIcebergOrderCreation();

        PlaceMiniOrderParam marketOrder = PlaceMiniOrderParam.builder()
                .orderNo(1002L)
                .delegateType("SELL")
                .orderType("MARKET")
                .delegateCount(new BigDecimal("1.5"))
                .createTime(new Date())
                .vip(false)
                .build();

        PlaceOrderParam marketOrderParam = new PlaceOrderParam();
        marketOrderParam.setSymbolId("BTC-USDT");
        marketOrderParam.setAccountId(12346L);
        marketOrderParam.setUid(12345L); // 添加UID字段
        marketOrderParam.setRequestTime(System.currentTimeMillis());
        marketOrderParam.setOrderList(Collections.singletonList(marketOrder));

        CommandResult result = placeOrderCommand.execute(marketOrderParam);

        assertNotNull(result);
        assertFalse(result.getMatchResult().getDealtRecords().isEmpty());

        CompactOrderBookEntry icebergOrder = matchEngine.getOrder(1001L);
        assertNotNull(icebergOrder);
        assertTrue(icebergOrder.isIceberg);
        assertEquals(CompactOrderBookEntry.toLong(new BigDecimal("0.5")), icebergOrder.remainingQty);
        assertEquals(CompactOrderBookEntry.toLong(new BigDecimal("8")), icebergOrder.icebergHiddenQuantity);
    }

    @Test
    void testIcebergOrderRefresh() {
        testIcebergOrderCreation();

        PlaceMiniOrderParam marketOrder = PlaceMiniOrderParam.builder()
                .orderNo(1003L)
                .delegateType("SELL")
                .orderType("MARKET")
                .delegateCount(new BigDecimal("2"))
                .createTime(new Date())
                .vip(false)
                .build();

        PlaceOrderParam marketOrderParam = new PlaceOrderParam();
        marketOrderParam.setSymbolId("BTC-USDT");
        marketOrderParam.setAccountId(12347L);
        marketOrderParam.setUid(12345L); // 添加UID字段
        marketOrderParam.setRequestTime(System.currentTimeMillis());
        marketOrderParam.setOrderList(Collections.singletonList(marketOrder));

        placeOrderCommand.execute(marketOrderParam);

        CompactOrderBookEntry icebergOrder = matchEngine.getOrder(1001L);
        assertNotNull(icebergOrder);
        assertTrue(icebergOrder.isIceberg);
        assertEquals(CompactOrderBookEntry.toLong(new BigDecimal("2")), icebergOrder.remainingQty);
        assertEquals(CompactOrderBookEntry.toLong(new BigDecimal("2")), icebergOrder.icebergDisplayQuantity);
        assertEquals(CompactOrderBookEntry.toLong(new BigDecimal("6")), icebergOrder.icebergHiddenQuantity);
    }

    @Test
    void testIcebergOrderValidation() {
        OrderValidator validator = new OrderValidator(symbolConfigService, orderBookService, rateLimiter);

        SymbolConfig config = new SymbolConfig();
        config.setTradingEnabled(true);
        config.setMinQuantity(new BigDecimal("0.1"));
        config.setMaxQuantity(new BigDecimal("10000"));
        config.setMinPrice(new BigDecimal("1"));
        config.setMaxPrice(new BigDecimal("100000"));
        config.setPricePrecision(2);
        config.setQuantityPrecision(8);
        when(symbolConfigService.getConfig("BTC-USDT")).thenReturn(config);

        PlaceMiniOrderParam invalidOrder = PlaceMiniOrderParam.builder()
                .orderNo(1004L)
                .delegateType("BUY")
                .orderType("LIMIT")
                .delegatePrice(new BigDecimal("50000"))
                .delegateCount(new BigDecimal("10"))
                .isIceberg(true)
                .icebergTotalQuantity(new BigDecimal("10"))
                .icebergDisplayQuantity(new BigDecimal("15")) // 显示量大于总量
                .icebergRefreshQuantity(new BigDecimal("2"))
                .createTime(new Date())
                .build();

        PlaceOrderParam invalidOrderParam = new PlaceOrderParam();
        invalidOrderParam.setSymbolId("BTC-USDT");
        invalidOrderParam.setAccountId(12348L);
        invalidOrderParam.setUid(12345L); // 添加UID字段
        invalidOrderParam.setOrderList(Collections.singletonList(invalidOrder));

        String error = validator.validate(invalidOrderParam);
        assertNotNull(error);
        assertTrue(error.contains("ICEBERG_DISPLAY_EXCEED_TOTAL"));
    }
}
