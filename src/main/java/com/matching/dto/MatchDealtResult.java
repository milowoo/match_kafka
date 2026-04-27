package com.matching.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Getter
@Setter
public class MatchDealtResult {
    private Long recordId;
    private Long orderId;
    private String businessLine;
    private String secondBusinessLine;
    private Long accountId;
    private String tokenId;
    private String symbolId;
    private String delegateType;
    private String orderType;
    private BigDecimal dealtCount;
    private BigDecimal dealtAmount;
    private BigDecimal dealtPrice;
    private String takerMakerFlag; // TAKER / MAKER
    private Long targetAccountId;
    private Long targetRecordId;
    private Date createTime;
    private boolean isMarketOrder = false;
    private boolean isTargetMarketOrder = false;
    private Long splitAccountId = 0L;
    private Integer marginType;
    private Long uid;
    private Long targetUid;
}