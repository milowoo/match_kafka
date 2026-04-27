package com.matching.model;

import org.eclipse.collections.impl.list.mutable.FastList;

/**
 * 深度更新数据模型
 */
public class DepthUpdate {
    private final String symbol;
    private final FastList<PriceLevel> bids;
    private final FastList<PriceLevel> asks;
    private final long timestamp;
    private final boolean isSnapshot;

    public DepthUpdate(String symbol, FastList<PriceLevel> bids, FastList<PriceLevel> asks,
                       long timestamp, boolean isSnapshot) {
        this.symbol = symbol;
        this.bids = bids;
        this.asks = asks;
        this.timestamp = timestamp;
        this.isSnapshot = isSnapshot;
    }

    public String getSymbol() { return symbol; }
    public FastList<PriceLevel> getBids() { return bids; }
    public FastList<PriceLevel> getAsks() { return asks; }
    public long getTimestamp() { return timestamp; }
    public boolean isSnapshot() { return isSnapshot; }

    @Override
    public String toString() {
        return String.format("DepthUpdate{symbol='%s', bids=%d, asks=%d, timestamp=%d, snapshot=%s}",
                symbol, bids.size(), asks.size(), timestamp, isSnapshot);
    }
}