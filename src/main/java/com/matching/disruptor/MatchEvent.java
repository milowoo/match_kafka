package com.matching.disruptor;

import com.matching.dto.CancelOrderParam;
import com.matching.dto.PlaceOrderParam;
import com.matching.enums.TradeCommandType;
import org.springframework.kafka.support.Acknowledgment;

public class MatchEvent {

    private TradeCommandType type;
    private String symbolId;
    private PlaceOrderParam placeOrderParam;
    private CancelOrderParam cancelOrderParam;
    private Acknowledgment ack;
    private String traceId;

    public void reset() {
        type = null;
        symbolId = null;
        placeOrderParam = null;
        cancelOrderParam = null;
        ack = null;
        traceId = null;
    }

    public TradeCommandType getType() {
        return type;
    }

    public void setType(TradeCommandType type) {
        this.type = type;
    }

    public String getSymbolId() {
        return symbolId;
    }

    public void setSymbolId(String symbolId) {
        this.symbolId = symbolId;
    }

    public PlaceOrderParam getPlaceOrderParam() {
        return placeOrderParam;
    }

    public void setPlaceOrderParam(PlaceOrderParam placeOrderParam) {
        this.placeOrderParam = placeOrderParam;
    }

    public CancelOrderParam getCancelOrderParam() {
        return cancelOrderParam;
    }

    public void setCancelOrderParam(CancelOrderParam cancelOrderParam) {
        this.cancelOrderParam = cancelOrderParam;
    }

    public Acknowledgment getAck() {
        return ack;
    }

    public void setAck(Acknowledgment ack) {
        this.ack = ack;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}