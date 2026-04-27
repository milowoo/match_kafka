package com.matching.command;

import com.matching.dto.*;
import com.matching.engine.CompactOrderBookEntry;
import com.matching.service.HAService;
import com.matching.service.OrderBookService;
import com.matching.service.orderbook.OrderOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CancelOrderCommandTest {

    @Mock private OrderBookService orderBookService;
    @Mock private HAService haService;

    private CancelOrderCommand command;
    private static final String SYMBOL = "BTCUSDT";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        command = new CancelOrderCommand(orderBookService, haService);
        when(orderBookService.exists(SYMBOL)).thenReturn(true);
        when(haService.isActive()).thenReturn(true);
        when(haService.getCurrentRole()).thenReturn("PRIMARY");
    }

    /**
     * 创建测试用的 CancelOrderParam，自动设置 uid
     */
    private CancelOrderParam createCancelParam(String symbolId, List<String> orderIds) {
        CancelOrderParam param = new CancelOrderParam();
        param.setSymbolId(symbolId);
        param.setOrderIds(orderIds);
        param.setUid(5001L);  // 测试用 uid
        return param;
    }

    /**
     * 创建测试用的全量取消参数
     */
    private CancelOrderParam createCancelAllParam(String symbolId, long accountId) {
        CancelOrderParam param = new CancelOrderParam();
        param.setSymbolId(symbolId);
        param.setAccountId(accountId);
        param.setCancelAll(true);
        param.setUid(5001L);  // 测试用 uid
        return param;
    }

    @Test
    @DisplayName("1. 单笔取消 + 状态验证")
    void testSingleOrderCancel() {
        CancelOrderParam param = createCancelParam(SYMBOL, Arrays.asList("1"));

        when(orderBookService.getCompactOrder(eq(SYMBOL), eq(1L))).thenReturn(createMockCompactOrder(1L, 1001L));
        when(orderBookService.cancelOrder(eq(SYMBOL), eq("1"))).thenReturn(Collections.emptyMap());

        CommandResult result = command.execute(param);

        assertNotNull(result);
        assertNotNull(result.getMatchResult());
        assertEquals(SYMBOL, result.getMatchResult().getSymbolId());
        assertEquals(1, result.getMatchResult().getDealtOrders().size());
        // 验证 uid 是否正确设置
        assertEquals(5001L, result.getMatchResult().getDealtOrders().get(0).getUid());
        verify(orderBookService, times(1)).cancelOrder(eq(SYMBOL), eq("1"));
    }

    @Test
    @DisplayName("2. 取消不存在的订单")
    void testCancelNonExistentOrder() {
        CancelOrderParam param = createCancelParam(SYMBOL, Arrays.asList("9999"));

        when(orderBookService.getCompactOrder(eq(SYMBOL), eq(9999L))).thenReturn(null);

        CommandResult result = command.execute(param);

        assertNotNull(result);
        assertTrue(result.getMatchResult().getDealtOrders().isEmpty());
        verify(orderBookService, never()).cancelOrder(eq(SYMBOL), eq("9999"));
    }

    @Test
    @DisplayName("3. 批量取消（部分存在）")
    void testBatchCancelPartialExists() {
        CancelOrderParam param = createCancelParam(SYMBOL, Arrays.asList("1", "9999", "2", "8888"));

        when(orderBookService.getCompactOrder(eq(SYMBOL), eq(1L))).thenReturn(createMockCompactOrder(1L, 1001L));
        when(orderBookService.getCompactOrder(eq(SYMBOL), eq(9999L))).thenReturn(null);
        when(orderBookService.getCompactOrder(eq(SYMBOL), eq(2L))).thenReturn(createMockCompactOrder(2L, 1001L));
        when(orderBookService.getCompactOrder(eq(SYMBOL), eq(8888L))).thenReturn(null);
        when(orderBookService.cancelOrder(eq(SYMBOL), eq("1"))).thenReturn(Collections.emptyMap());
        when(orderBookService.cancelOrder(eq(SYMBOL), eq("2"))).thenReturn(Collections.emptyMap());

        CommandResult result = command.execute(param);

        assertEquals(2, result.getMatchResult().getDealtOrders().size());
        verify(orderBookService, times(1)).cancelOrder(eq(SYMBOL), eq("1"));
        verify(orderBookService, times(1)).cancelOrder(eq(SYMBOL), eq("2"));
        verify(orderBookService, never()).cancelOrder(eq(SYMBOL), eq("9999"));
        verify(orderBookService, never()).cancelOrder(eq(SYMBOL), eq("8888"));
    }

    @Test
    @DisplayName("4. 按账户全部取消")
    void testCancelAllByAccount() {
        CancelOrderParam param = createCancelAllParam(SYMBOL, 1001L);

        long qty = CompactOrderBookEntry.toLong(new BigDecimal("1.0"));
        OrderOperations.CancelAllResult cancelAllResult = new OrderOperations.CancelAllResult(
                Collections.emptyMap(),
                Arrays.asList(
                        new OrderOperations.OrderSnapshot(1L, 1001L, qty, qty),
                        new OrderOperations.OrderSnapshot(2L, 1001L, qty, qty)
                ),
                Arrays.asList("1", "2")
        );
        when(orderBookService.cancelAllByAccount(eq(SYMBOL), eq(1001L))).thenReturn(cancelAllResult);

        CommandResult result = command.execute(param);

        assertEquals(2, result.getMatchResult().getDealtOrders().size());
        // 验证 uid 是否正确设置
        result.getMatchResult().getDealtOrders().forEach(order ->
                assertEquals(5001L, order.getUid())
        );
        verify(orderBookService, times(1)).cancelAllByAccount(eq(SYMBOL), eq(1001L));
    }

    @Test
    @DisplayName("5. 空列表取消")
    void testCancelEmptyList() {
        CancelOrderParam param = createCancelParam(SYMBOL, Collections.emptyList());

        CommandResult result = command.execute(param);

        assertTrue(result.getMatchResult().getDealtOrders().isEmpty());
        verify(orderBookService, never()).cancelOrder(any(), any());
    }

    @Test
    @DisplayName("6. SyncPayload正确性")
    void testSyncPayloadCorrectness() {
        CancelOrderParam param = createCancelParam(SYMBOL, Arrays.asList("1"));

        when(orderBookService.getCompactOrder(eq(SYMBOL), eq(1L))).thenReturn(createMockCompactOrder(1L, 1001L));
        when(orderBookService.cancelOrder(eq(SYMBOL), eq("1"))).thenReturn(Collections.emptyMap());

        CommandResult result = command.execute(param);

        assertNotNull(result.getSyncPayload());
        assertEquals(SYMBOL, result.getSyncPayload().getSymbolId());
    }

    @Test
    @DisplayName("7. 批量取消测试")
    void testBatchCancel() {
        CancelOrderParam param = createCancelParam(SYMBOL, Arrays.asList("10", "11", "12", "13", "14"));

        for (int i = 10; i <= 14; i++) {
            when(orderBookService.getCompactOrder(eq(SYMBOL), eq((long) i))).thenReturn(createMockCompactOrder(i, 1001L));
            when(orderBookService.cancelOrder(eq(SYMBOL), eq(String.valueOf(i)))).thenReturn(Collections.emptyMap());
        }

        CommandResult result = command.execute(param);

        assertEquals(5, result.getMatchResult().getDealtOrders().size());
        for (int i = 10; i <= 14; i++) {
            verify(orderBookService, times(1)).cancelOrder(eq(SYMBOL), eq(String.valueOf(i)));
        }
    }

    private CompactOrderBookEntry createMockCompactOrder(long orderId, long accountId) {
        CompactOrderBookEntry entry = new CompactOrderBookEntry();
        entry.orderId = orderId;
        entry.accountId = accountId;
        entry.uid = 5001L;  // 测试用 uid
        entry.price = CompactOrderBookEntry.toLong(new BigDecimal("50000"));
        entry.quantity = CompactOrderBookEntry.toLong(new BigDecimal("1.0"));
        entry.remainingQty = entry.quantity;
        entry.side = CompactOrderBookEntry.BUY;
        entry.requestTime = System.currentTimeMillis();
        return entry;
    }
}
