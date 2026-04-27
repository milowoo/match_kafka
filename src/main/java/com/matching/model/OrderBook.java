package com.matching.model;

import org.eclipse.collections.api.map.primitive.MutableLongLongMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;

/**
 * 订单簿数据模型
 */
public class OrderBook {
    private final MutableLongLongMap buyOrders;
    private final MutableLongLongMap sellOrders;

    public OrderBook() {
        this.buyOrders = new LongLongHashMap();
        this.sellOrders = new LongLongHashMap();
    }

    public OrderBook(MutableLongLongMap buyOrders, MutableLongLongMap sellOrders) {
        this.buyOrders = buyOrders;
        this.sellOrders = sellOrders;
    }

    public MutableLongLongMap getBuyOrders() { return buyOrders; }
    public MutableLongLongMap getSellOrders() { return sellOrders; }

    public void addBuyOrder(long price, long quantity) {
        buyOrders.put(price, buyOrders.get(price) + quantity);
    }

    public void addSellOrder(long price, long quantity) {
        sellOrders.put(price, sellOrders.get(price) + quantity);
    }

    public void removeBuyOrder(long price, long quantity) {
        long remaining = buyOrders.get(price) - quantity;
        if (remaining <= 0) {
            buyOrders.remove(price);
        } else {
            buyOrders.put(price, remaining);
        }
    }

    public void removeSellOrder(long price, long quantity) {
        long remaining = sellOrders.get(price) - quantity;
        if (remaining <= 0) {
            sellOrders.remove(price);
        } else {
            sellOrders.put(price, remaining);
        }
    }

    public void clear() {
        buyOrders.clear();
        sellOrders.clear();
    }

    @Override
    public String toString() {
        return String.format("OrderBook{buyLevels=%d, sellLevels=%d}",
                buyOrders.size(), sellOrders.size());
    }
}