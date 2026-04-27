package com.matching.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Getter
@Setter
public class MatchCreateOrderResult {
    private Long id;
    private Long accountId;
    private String secondBusinessLine;
    private String tokenId;
    private String symbolId;
    private String delegateType;
    private String orderType;
    private Integer controlValue;
    private BigDecimal delegateCount;
    private BigDecimal delegateAmount;
    private BigDecimal delegatePrice;
    private BigDecimal dealtCount;
    private BigDecimal dealtAmount;
    private BigDecimal averageDealPrice;
    private String status; // NEW, PENDING, PARTIAL_FILLED, FILLED, CANCELLED
    private String cancelReason;
    private String businessSource;
    private String enterPointSource;
    private Date createTime;
    private Date updateTime;
    private String params;
    private Long version;
    private Long uid;
}