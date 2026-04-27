package com.matching.model;

/**
 * 价格档位数据模型
 */
public class PriceLevel {
    private final long price;
    private final long quantity;

    public PriceLevel(long price, long quantity) {
        this.price = price;
        this.quantity = quantity;
    }

    public long getPrice() { return price; }
    public long getQuantity() { return quantity; }

    @Override
    public String toString() {
        return String.format("PriceLevel{price=%d, quantity=%d}", price, quantity);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PriceLevel that = (PriceLevel) o;
        return price == that.price && quantity == that.quantity;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(price) * 31 + Long.hashCode(quantity);
    }
}