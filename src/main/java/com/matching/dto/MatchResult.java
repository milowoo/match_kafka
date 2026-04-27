package com.matching.dto;

import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.LinkedList;
import java.util.List;

@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchResult {
    @Builder.Default private List<MatchDealtResult> dealtRecords = new LinkedList<>();
    @Builder.Default private List<MatchOrderResult> dealtOrders = new LinkedList<>();
    @Builder.Default private List<MatchCreateOrderResult> createOrders = new LinkedList<>();
    private String symbolId;

    public List<MatchDealtResult> getDealtRecords() {
        return dealtRecords;
    }

    public void setDealtRecords(List<MatchDealtResult> dealtRecords) {
        this.dealtRecords = dealtRecords != null ? dealtRecords : new LinkedList<>();
    }

    public List<MatchOrderResult> getDealtOrders() {
        return dealtOrders;
    }

    public void setDealtOrders(List<MatchOrderResult> dealtOrders) {
        this.dealtOrders = dealtOrders != null ? dealtOrders : new LinkedList<>();
    }

    public List<MatchCreateOrderResult> getCreateOrders() {
        return createOrders;
    }

    public void setCreateOrders(List<MatchCreateOrderResult> createOrders) {
        this.createOrders = createOrders != null ? createOrders : new LinkedList<>();
    }

    public String getSymbolId() {
        return symbolId;
    }

    public void setSymbolId(String symbolId) {
        this.symbolId = symbolId;
    }
}