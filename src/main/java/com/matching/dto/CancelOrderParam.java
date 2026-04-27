package com.matching.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelOrderParam {
    private String symbolId;
    private List<String> orderIds;
    private Long accountId;
    private boolean cancelAll;
    private Long uid;        // 用户级别，撮合结果按 uid hash 路由到下游 trade-engine
}