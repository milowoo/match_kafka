package com.matching.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Date;

public class MatchOrderResult {
    private Long orderId;
    private Long accountId;
    private String businessLine;
    private String secondBusinessLine;
    private String tokenId;
    private String symbolId;
    private BigDecimal dealtCount;
    private BigDecimal remainCount;
    private BigDecimal remainAmount;
    private String status; // NEW, PENDING, PARTIAL_FILLED, FILLED, CANCELLED
    private String cancelReason;
    private String params;
    private Date updateTime;
    private Integer controlValue;
    private Long uid;

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getBusinessLine() {
        return businessLine;
    }

    public void setBusinessLine(String businessLine) {
        this.businessLine = businessLine;
    }

    public String getSecondBusinessLine() {
        return secondBusinessLine;
    }

    public void setSecondBusinessLine(String secondBusinessLine) {
        this.secondBusinessLine = secondBusinessLine;
    }

    public String getTokenId() {
        return tokenId;
    }

    public void setTokenId(String tokenId) {
        this.tokenId = tokenId;
    }

    public String getSymbolId() {
        return symbolId;
    }

    public void setSymbolId(String symbolId) {
        this.symbolId = symbolId;
    }

    public BigDecimal getDealtCount() {
        return dealtCount;
    }

    public void setDealtCount(BigDecimal dealtCount) {
        this.dealtCount = dealtCount;
    }

    public BigDecimal getRemainCount() {
        return remainCount;
    }

    public void setRemainCount(BigDecimal remainCount) {
        this.remainCount = remainCount;
    }

    public BigDecimal getRemainAmount() {
        return remainAmount;
    }

    public void setRemainAmount(BigDecimal remainAmount) {
        this.remainAmount = remainAmount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public Integer getControlValue() {
        return controlValue;
    }

    public void setControlValue(Integer controlValue) {
        this.controlValue = controlValue;
    }

    public Long getUid() {
        return uid;
    }

    public void setUid(Long uid) {
        this.uid = uid;
    }
}