package com.matching.controller;

import com.matching.dto.ApiResult;
import com.matching.dto.OrderBookEntry;
import com.matching.ha.InstanceLeaderElection;
import com.matching.service.OrderBookService;
import com.matching.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.net.HttpURLConnection;
import java.net.URL;

@RestController
@RequestMapping("/api/v1")
@Slf4j
public class TradeController {

    @Value("${matching.ha.peer-url:}")
    private String peerUrl;

    @Value("${matching.ha.health-check-timeout-ms:2000}")
    private int timeoutMs;

    private final OrderBookService orderBookService;
    private final InstanceLeaderElection leaderElection;

    public TradeController(OrderBookService orderBookService,
                           InstanceLeaderElection leaderElection) {
        this.orderBookService = orderBookService;
        this.leaderElection = leaderElection;
    }

    @GetMapping("/order/{symbolId}/{orderId}")
    public ApiResult<OrderBookEntry> getOrder(@PathVariable String symbolId, @PathVariable String orderId) {
        try {
            // Active: query local in-memory engine
            if (leaderElection.isActive()) {
                OrderBookEntry entry = orderBookService.getOrder(symbolId, orderId);
                if (entry == null) {
                    return ApiResult.error("Order not found (may be filled or cancelled)");
                }
                return ApiResult.ok(entry);
            }

            // Standby: proxy to Active peer
            if (peerUrl == null || peerUrl.isEmpty()) {
                return ApiResult.error("This instance is STANDBY and no peer-url configured");
            }
            return proxyToPeer(symbolId, orderId);
        } catch (Exception e) {
            log.error("Failed to query order, symbolId: {}, orderId: {}", symbolId, orderId, e);
            return ApiResult.error("Query failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ApiResult<OrderBookEntry> proxyToPeer(String symbolId, String orderId) {
        try {
            URL url = new URL(peerUrl + "/api/v1/order/" + symbolId + "/" + orderId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return ApiResult.error("Peer returned HTTP " + code);
            }

            String body = new String(conn.getInputStream().readAllBytes());
            conn.disconnect();
            return JsonUtil.jackson().readValue(body,
                    JsonUtil.jackson().getTypeFactory().constructParametricType(ApiResult.class, OrderBookEntry.class));
        } catch (Exception e) {
            log.error("Failed to proxy getOrder to peer: {}", peerUrl, e);
            return ApiResult.error("Peer unreachable: " + e.getMessage());
        }
    }
}