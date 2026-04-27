package com.matching.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Data
@Getter
@Setter
public class PlaceOrderParam {
    private long requestTime;
    private String businessLine;
    private String secondBusinessLine;
    private Long accountId;
    private String tokenId;
    private String symbolId;
    private BigDecimal openPrice;
    private boolean symbolOpen = true;
    private List<PlaceMiniOrderParam> orderList;
    private Long uid;
}