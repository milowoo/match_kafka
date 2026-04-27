package com.matching.command;

import com.matching.dto.PlaceOrderParam;
import com.matching.dto.PlaceMiniOrderParam;
import com.matching.dto.MatchResult;
import com.matching.dto.MatchCreateOrderResult;
import com.matching.dto.OrderBookEntry;
import com.matching.engine.CompactOrderBookEntry;
import com.matching.engine.MatchEngine;
import com.matching.enums.OrderType;

import java.math.BigDecimal;
import java.util.*;

/**
 * 撮合过程上下文：封装单次撮合的所有共享状态。生命周期：一次 PlaceOrderCommand.execute 调用。
 */
public class MatchContext {

    // --- 输入 ---
    public final String symbolId;
    public final PlaceOrderParam message;
    public final PlaceMiniOrderParam miniOrder;
    public final OrderType orderType;
    public final MatchEngine matchEngine;
    public final byte side;
    public final String sideStr;
    public final String oppositeSide;
    public CompactOrderBookEntry taker;

    // --- 输出 ---
    public MatchResult matchResult;
    public MatchCreateOrderResult createOrder;
    public final Map<String, Set<BigDecimal>> changedLevels = new HashMap<>();
    public final List<OrderBookEntry> addedOrders = new ArrayList<>();
    public final List<String> removedOrderIds = new ArrayList<>();

    public long lastTradePriceLong;
    public long totalDealtAmountLong = 0;
    public long totalDealtCountLong = 0;
    public String protectionCancelReason;

    public MatchContext(String symbolId, PlaceOrderParam message, PlaceMiniOrderParam miniOrder,
                        OrderType orderType, MatchEngine matchEngine) {
        this.symbolId = symbolId;
        this.message = message;
        this.miniOrder = miniOrder;
        this.orderType = orderType;
        this.matchEngine = matchEngine;

        this.matchResult = MatchResult.builder().symbolId(symbolId).build();
        this.side = miniOrder.getDelegateType().toUpperCase().contains("BUY")
                ? CompactOrderBookEntry.BUY : CompactOrderBookEntry.SELL;
        this.sideStr = side == CompactOrderBookEntry.BUY ? "buy" : "sell";
        this.oppositeSide = side == CompactOrderBookEntry.BUY ? "sell" : "buy";
    }

    public boolean isMarket() { return orderType == OrderType.MARKET; }
    public boolean isIOC() { return orderType == OrderType.LIMIT_IOC; }
    public boolean isFOK() { return orderType == OrderType.LIMIT_FOK; }
    public boolean isPostOnly() { return orderType == OrderType.LIMIT_POST_ONLY; }
}