package com.matching.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class PlaceMiniOrderParam {

    private Long orderNo;
    private String delegateType;
    private String orderType;
    private BigDecimal delegateCount;
    private BigDecimal delegateAmount;
    private BigDecimal delegatePrice;
    private int controlValue;
    private String businessSource;
    private String enterPointSource;
    private Date createTime;
    private String params;
    private BigDecimal delegateLeverage;
    private String cancelReason;
    private boolean vip;

    // 冰山单相关字段
    private boolean isIceberg;
    private BigDecimal icebergTotalQuantity;    // 冰山单总量
    private BigDecimal icebergDisplayQuantity;  // 当前显示量
    private BigDecimal icebergRefreshQuantity;  // 刷新量
}