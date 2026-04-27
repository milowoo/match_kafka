package com.matching.config.symbol;

import java.math.BigDecimal;

public class SymbolConfig {

    private String symbolId;

    // Precision
    private int pricePrecision;    // e.g. 2 means 0.01 tick
    private int quantityPrecision; // e.g. 8 means 0.00000001 lot

    // Limits
    private BigDecimal minQuantity;
    private BigDecimal maxQuantity;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private BigDecimal minNotional; // price * qty minimum (e.g. 10 USDT)

    // Price protection
    private BigDecimal priceDeviationRate; // e.g. 0.1 = 10% max deviation from last trade price

    // Order limits
    private int maxOpenOrders;    // per account per symbol

    // Trading status
    private boolean tradingEnabled;

    // 大单穿仓保护
    private int maxPriceLevels;   // 单笔最大穿越价格档位, 0=不限制
    private BigDecimal maxSlippageRate; // 市价最大滑点比例, e.g. 0.05 = 5%, null=不限制

    public String getSymbolId() {
        return symbolId;
    }

    public void setSymbolId(String symbolId) {
        this.symbolId = symbolId;
    }

    public int getPricePrecision() {
        return pricePrecision;
    }

    public void setPricePrecision(int pricePrecision) {
        this.pricePrecision = pricePrecision;
    }

    public int getQuantityPrecision() {
        return quantityPrecision;
    }

    public void setQuantityPrecision(int quantityPrecision) {
        this.quantityPrecision = quantityPrecision;
    }

    public BigDecimal getMinQuantity() {
        return minQuantity;
    }

    public void setMinQuantity(BigDecimal minQuantity) {
        this.minQuantity = minQuantity;
    }

    public BigDecimal getMaxQuantity() {
        return maxQuantity;
    }

    public void setMaxQuantity(BigDecimal maxQuantity) {
        this.maxQuantity = maxQuantity;
    }

    public BigDecimal getMinPrice() {
        return minPrice;
    }

    public void setMinPrice(BigDecimal minPrice) {
        this.minPrice = minPrice;
    }

    public BigDecimal getMaxPrice() {
        return maxPrice;
    }

    public void setMaxPrice(BigDecimal maxPrice) {
        this.maxPrice = maxPrice;
    }

    public BigDecimal getMinNotional() {
        return minNotional;
    }

    public void setMinNotional(BigDecimal minNotional) {
        this.minNotional = minNotional;
    }

    public BigDecimal getPriceDeviationRate() {
        return priceDeviationRate;
    }

    public void setPriceDeviationRate(BigDecimal priceDeviationRate) {
        this.priceDeviationRate = priceDeviationRate;
    }

    public int getMaxOpenOrders() {
        return maxOpenOrders;
    }

    public void setMaxOpenOrders(int maxOpenOrders) {
        this.maxOpenOrders = maxOpenOrders;
    }

    public boolean isTradingEnabled() {
        return tradingEnabled;
    }

    public void setTradingEnabled(boolean tradingEnabled) {
        this.tradingEnabled = tradingEnabled;
    }

    public int getMaxPriceLevels() {
        return maxPriceLevels;
    }

    public void setMaxPriceLevels(int maxPriceLevels) {
        this.maxPriceLevels = maxPriceLevels;
    }

    public BigDecimal getMaxSlippageRate() {
        return maxSlippageRate;
    }

    public void setMaxSlippageRate(BigDecimal maxSlippageRate) {
        this.maxSlippageRate = maxSlippageRate;
    }
}