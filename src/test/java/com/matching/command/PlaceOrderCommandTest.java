package com.matching.command;

import com.matching.dto.*;
import com.matching.service.HAService;
import com.matching.service.OrderBookService;
import com.matching.service.OrderValidator;
import com.matching.config.symbol.SymbolConfigService;
import com.matching.config.symbol.SymbolConfig;
import com.matching.util.SnowflakeIdGenerator;
import com.matching.engine.MatchEngine;
import com.matching.engine.CompactOrderBookEntry;
import com.matching.engine.PriceLevelBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class PlaceOrderCommandTest {

    @Mock private OrderBookService orderBookService;
    @Mock private OrderValidator orderValidator;
    @Mock private SymbolConfigService symbolConfigService;
    @Mock private SnowflakeIdGenerator snowflakeIdGenerator;
    @Mock private PriceLevelBook buyBook;
    @Mock private PriceLevelBook sellBook;
    @Mock private MatchEngine engine;
    @Mock private HAService haService;
    @Spy  private MatchResultBuilder resultBuilder = new MatchResultBuilder();

    private PlaceOrderCommand command;
    private static final String SYMBOL = "BTCUSDT";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TakerOrderFactory takerOrderFactory = new TakerOrderFactory();
        MatchExecutor matchExecutor = new MatchExecutor(symbolConfigService, orderBookService, snowflakeIdGenerator, resultBuilder);
        command = new PlaceOrderCommand(orderBookService, orderValidator, takerOrderFactory, matchExecutor, resultBuilder, haService);
        command.setEngine(engine);

        CompactOrderBookEntry mockEntry = new CompactOrderBookEntry();
        when(engine.acquireEntry()).thenReturn(mockEntry);
        when(engine.getAccountOrderCount(anyLong())).thenReturn(0);
        when(engine.getBuyBook()).thenReturn(buyBook);
        when(engine.getSellBook()).thenReturn(sellBook);
        when(buyBook.levels()).thenReturn(java.util.Collections.emptySet());
        when(sellBook.levels()).thenReturn(java.util.Collections.emptySet());
        when(engine.addOrder(any())).thenReturn(true);

        SymbolConfig config = new SymbolConfig();
        config.setTradingEnabled(true);
        when(symbolConfigService.getConfig(SYMBOL)).thenReturn(config);
        when(orderBookService.exists(SYMBOL)).thenReturn(true);
        when(orderValidator.validate(any())).thenReturn(null);
        when(snowflakeIdGenerator.nextId()).thenReturn(1000L, 1001L, 1002L, 1003L);
        when(haService.isActive()).thenReturn(true);
    }

    @Test
    @DisplayName("1. 基本下单测试")
    void testBasicPlaceOrder() {
        PlaceOrderParam param = createPlaceOrderParam("1", 1001L, "BUY", "50000", "1.0", "LIMIT");

        CommandResult result = command.execute(param);

        assertNotNull(result);
        assertNotNull(result.getMatchResult());
        assertEquals(SYMBOL, result.getMatchResult().getSymbolId());
        assertEquals(1, result.getMatchResult().getCreateOrders().size());

        MatchCreateOrderResult createOrder = result.getMatchResult().getCreateOrders().get(0);
        assertEquals(1L, createOrder.getId());
        assertEquals(1001L, createOrder.getAccountId());
    }

    @Test
    @DisplayName("2. 验证失败拒绝")
    void testValidationFailure() {
        when(orderValidator.validate(any())).thenReturn("Invalid price");

        PlaceOrderParam param = createPlaceOrderParam("1", 1001L, "BUY", "0", "1.0", "LIMIT");
        CommandResult result = command.execute(param);

        assertNotNull(result);
        assertNotNull(result.getMatchResult());
        assertEquals(1, result.getMatchResult().getCreateOrders().size());

        MatchCreateOrderResult createOrder = result.getMatchResult().getCreateOrders().get(0);
        assertEquals("REJECTED", createOrder.getStatus());
        assertEquals("Invalid price", createOrder.getCancelReason());
    }

    @Test
    @DisplayName("3. SyncPayload正确性验证")
    void testSyncPayloadCorrectness() {
        PlaceOrderParam param = createPlaceOrderParam("1", 1001L, "BUY", "50000", "1.0", "LIMIT");
        CommandResult result = command.execute(param);

        assertNotNull(result);
        assertNotNull(result.getSyncPayload());
        assertEquals(SYMBOL, result.getSyncPayload().getSymbolId());
    }

    private PlaceOrderParam createPlaceOrderParam(String clientOrderId, Long accountId, String side,
                                                  String price, String quantity, String type) {
        PlaceOrderParam param = new PlaceOrderParam();
        param.setSymbolId(SYMBOL);
        param.setAccountId(accountId);
        param.setUid(12345L); // 添加UID字段
        param.setBusinessLine("SPOT");
        param.setTokenId("TOKEN123");
        param.setRequestTime(System.currentTimeMillis());

        PlaceMiniOrderParam miniOrder = new PlaceMiniOrderParam();
        miniOrder.setOrderNo(Long.parseLong(clientOrderId));
        miniOrder.setDelegateType(side);
        miniOrder.setOrderType(type);
        miniOrder.setDelegateCount(new BigDecimal(quantity));
        if (price != null) {
            miniOrder.setDelegatePrice(new BigDecimal(price));
        }
        miniOrder.setCreateTime(new Date());

        param.setOrderList(Arrays.asList(miniOrder));
        return param;
    }
}
