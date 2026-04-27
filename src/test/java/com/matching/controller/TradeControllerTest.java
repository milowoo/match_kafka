package com.matching.controller;

import com.matching.dto.OrderBookEntry;
import com.matching.service.OrderBookService;
import com.matching.ha.InstanceLeaderElection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * TradeController 集成测试类
 */
@WebMvcTest(TradeController.class)
public class TradeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderBookService orderBookService;

    @MockBean
    private InstanceLeaderElection leaderElection;

    /**
     * 测试场景：Active - 本地查询
     */
    @Test
    @DisplayName("Active - order found")
    void testGetOrder_Active_Found() throws Exception {
        when(leaderElection.isActive()).thenReturn(true);

        OrderBookEntry entry = OrderBookEntry.builder()
                .clientOrderId("1001")
                .accountId(1001L)
                .symbolId("BTCUSDT")
                .side("buy")
                .price(new BigDecimal("50000"))
                .quantity(new BigDecimal("1.5"))
                .remainingQuantity(new BigDecimal("1.0"))
                .requestTime(System.currentTimeMillis())
                .build();

        when(orderBookService.getOrder(eq("BTCUSDT"), eq("1001"))).thenReturn(entry);

        mockMvc.perform(get("/api/v1/order/BTCUSDT/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.clientOrderId").value("1001"))
                .andExpect(jsonPath("$.data.side").value("buy"));
    }

    @Test
    @DisplayName("Active - order not found")
    void testGetOrder_Active_NotFound() throws Exception {
        when(leaderElection.isActive()).thenReturn(true);
        when(orderBookService.getOrder(eq("BTCUSDT"), eq("99999"))).thenReturn(null);

        mockMvc.perform(get("/api/v1/order/BTCUSDT/99999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(-1))
                .andExpect(jsonPath("$.message").value("Order not found (may be filled or cancelled)"));
    }

    @Test
    @DisplayName("Active - service error")
    void testGetOrder_Active_Error() throws Exception {
        when(leaderElection.isActive()).thenReturn(true);
        when(orderBookService.getOrder(eq("BTCUSDT"), eq("1001")))
                .thenThrow(new RuntimeException("Engine error"));

        mockMvc.perform(get("/api/v1/order/BTCUSDT/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(-1))
                .andExpect(jsonPath("$.message").value("Query failed: Engine error"));
    }

    /**
     * 测试场景：Standby - 代理到对等节点
     */
    @Test
    @DisplayName("Standby - no peer-url configured returns error")
    void testGetOrder_Standby_NoPeerUrl() throws Exception {
        when(leaderElection.isActive()).thenReturn(false);
        // peer-url defaults to empty string

        mockMvc.perform(get("/api/v1/order/BTCUSDT/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(-1))
                .andExpect(jsonPath("$.message").value("This instance is STANDBY and no peer-url configured"));
    }
}