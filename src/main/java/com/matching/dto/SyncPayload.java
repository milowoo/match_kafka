package com.matching.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SyncPayload {
    private String symbolId;
    private Map<String, Set<BigDecimal>> changedPriceLevels;
    private List<OrderBookEntry> addedOrders;
    private List<String> removedOrderIds;
    private MatchResult matchResult;

    public SyncPayload(String symbolId, Map<String, Set<BigDecimal>> changedPriceLevels,
                       List<OrderBookEntry> addedOrders, List<String> removedOrderIds) {
        this(symbolId, changedPriceLevels, addedOrders, removedOrderIds, null);
    }

    public SyncPayload(String symbolId, Map<String, Set<BigDecimal>> changedPriceLevels,
                       List<OrderBookEntry> addedOrders, List<String> removedOrderIds,
                       MatchResult matchResult) {
        this.symbolId = symbolId;
        this.changedPriceLevels = changedPriceLevels;
        this.addedOrders = addedOrders;
        this.removedOrderIds = removedOrderIds;
        this.matchResult = matchResult;
    }

    public String getSymbolId() {
        return symbolId;
    }

    public void setSymbolId(String symbolId) {
        this.symbolId = symbolId;
    }

    public Map<String, Set<BigDecimal>> getChangedPriceLevels() {
        return changedPriceLevels;
    }

    public void setChangedPriceLevels(Map<String, Set<BigDecimal>> changedPriceLevels) {
        this.changedPriceLevels = changedPriceLevels;
    }

    public List<OrderBookEntry> getAddedOrders() {
        return addedOrders;
    }

    public void setAddedOrders(List<OrderBookEntry> addedOrders) {
        this.addedOrders = addedOrders;
    }

    public List<String> getRemovedOrderIds() {
        return removedOrderIds;
    }

    public void setRemovedOrderIds(List<String> removedOrderIds) {
        this.removedOrderIds = removedOrderIds;
    }

    public MatchResult getMatchResult() {
        return matchResult;
    }

    public void setMatchResult(MatchResult matchResult) {
        this.matchResult = matchResult;
    }
}