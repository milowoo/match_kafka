package com.matching.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderBookEntry {
    private String clientOrderId;
    private Long accountId;
    private Long uid;
    private String symbolId;
    private String side;
    private BigDecimal price;
    private BigDecimal quantity;
    private BigDecimal remainingQuantity;
    private long requestTime;
    private boolean vip;
    private boolean isIceberg;
    private BigDecimal icebergTotalQuantity;
    private BigDecimal icebergDisplayQuantity;
    private BigDecimal icebergHiddenQuantity;
    private BigDecimal icebergRefreshQuantity;
}