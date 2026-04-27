package com.matching.util;

import com.matching.dto.*;
import com.matching.proto.MatchingProto.*;
import java.util.Date;

import static com.matching.util.ProtoConvertUtil.*;

/**
 * 撮合结果 DTO ↔ Proto 转换（出口：发送给下游）
 */
final class MatchResultProtoConverter {

    private MatchResultProtoConverter() {}

    static PbMatchResult toProto(MatchResult r) {
        PbMatchResult.Builder b = PbMatchResult.newBuilder().setSymbolId(r.getSymbolId());
        for (MatchDealtResult d : r.getDealtRecords()) b.addDealtRecords(toProtoDealt(d));
        for (MatchOrderResult o : r.getDealtOrders()) b.addDealtOrders(toProtoOrder(o));
        for (MatchCreateOrderResult c : r.getCreateOrders()) b.addCreateOrders(toProtoCreate(c));
        return b.build();
    }

    static MatchResult fromProto(PbMatchResult pb) {
        MatchResult r = MatchResult.builder().symbolId(pb.getSymbolId()).build();
        for (PbMatchDealtResult d : pb.getDealtRecordsList()) r.getDealtRecords().add(fromProtoDealt(d));
        for (PbMatchOrderResult o : pb.getDealtOrdersList()) r.getDealtOrders().add(fromProtoOrder(o));
        for (PbMatchCreateOrderResult c : pb.getCreateOrdersList()) r.getCreateOrders().add(fromProtoCreate(c));
        return r;
    }

    static byte[] serialize(MatchResult r) {
        return toProto(r).toByteArray();
    }

    static MatchResult deserialize(byte[] data) throws Exception {
        return fromProto(PbMatchResult.parseFrom(data));
    }

    // ==================== MatchDealtResult ====================
    private static PbMatchDealtResult toProtoDealt(MatchDealtResult d) {
        return PbMatchDealtResult.newBuilder()
                .setRecordId(d.getRecordId() != null ? d.getRecordId() : 0)
                .setOrderId(d.getOrderId() != null ? d.getOrderId() : 0)
                .setBusinessLine(s(d.getBusinessLine()))
                .setSecondBusinessLine(s(d.getSecondBusinessLine()))
                .setAccountId(d.getAccountId() != null ? d.getAccountId() : 0)
                .setTokenId(s(d.getTokenId()))
                .setSymbolId(s(d.getSymbolId()))
                .setDelegateType(s(d.getDelegateType()))
                .setOrderType(s(d.getOrderType()))
                .setDealtCount(bd(d.getDealtCount()))
                .setDealtAmount(bd(d.getDealtAmount()))
                .setDealtPrice(bd(d.getDealtPrice()))
                .setTakerMakerFlag(s(d.getTakerMakerFlag()))
                .setTargetAccountId(d.getTargetAccountId() != null ? d.getTargetAccountId() : 0)
                .setTargetRecordId(d.getTargetRecordId() != null ? d.getTargetRecordId() : 0)
                .setCreateTime(d.getCreateTime() != null ? d.getCreateTime().getTime() : 0)
                .setIsMarketOrder(d.isMarketOrder())
                .setIsTargetMarketOrder(d.isTargetMarketOrder())
                .setSplitAccountId(d.getSplitAccountId() != null ? d.getSplitAccountId() : 0)
                .setMarginType(d.getMarginType() != null ? d.getMarginType() : 0)
                .setUid(d.getUid() != null ? d.getUid() : 0)
                .setTargetUid(d.getTargetUid() != null ? d.getTargetUid() : 0)
                .build();
    }

    private static MatchDealtResult fromProtoDealt(PbMatchDealtResult pb) {
        MatchDealtResult d = new MatchDealtResult();
        d.setRecordId(pb.getRecordId());
        d.setOrderId(pb.getOrderId());
        d.setBusinessLine(toStr(pb.getBusinessLine()));
        d.setSecondBusinessLine(toStr(pb.getSecondBusinessLine()));
        d.setAccountId(pb.getAccountId());
        d.setTokenId(toStr(pb.getTokenId()));
        d.setSymbolId(toStr(pb.getSymbolId()));
        d.setDelegateType(toStr(pb.getDelegateType()));
        d.setOrderType(toStr(pb.getOrderType()));
        d.setDealtCount(toBd(pb.getDealtCount()));
        d.setDealtAmount(toBd(pb.getDealtAmount()));
        d.setDealtPrice(toBd(pb.getDealtPrice()));
        d.setTakerMakerFlag(toStr(pb.getTakerMakerFlag()));
        d.setTargetAccountId(pb.getTargetAccountId());
        d.setTargetRecordId(pb.getTargetRecordId());
        d.setCreateTime(pb.getCreateTime() > 0 ? new Date(pb.getCreateTime()) : null);
        d.setMarketOrder(pb.getIsMarketOrder());
        d.setTargetMarketOrder(pb.getIsTargetMarketOrder());
        d.setSplitAccountId(pb.getSplitAccountId());
        d.setMarginType(pb.getMarginType());
        d.setUid(pb.getUid() != 0 ? pb.getUid() : null);
        d.setTargetUid(pb.getTargetUid() != 0 ? pb.getTargetUid() : null);
        return d;
    }

    // ==================== MatchOrderResult ====================
    private static PbMatchOrderResult toProtoOrder(MatchOrderResult o) {
        return PbMatchOrderResult.newBuilder()
                .setOrderId(o.getOrderId() != null ? o.getOrderId() : 0)
                .setAccountId(o.getAccountId() != null ? o.getAccountId() : 0)
                .setBusinessLine(s(o.getBusinessLine()))
                .setSecondBusinessLine(s(o.getSecondBusinessLine()))
                .setTokenId(s(o.getTokenId()))
                .setSymbolId(s(o.getSymbolId()))
                .setDealtCount(bd(o.getDealtCount()))
                .setRemainCount(bd(o.getRemainCount()))
                .setRemainAmount(bd(o.getRemainAmount()))
                .setStatus(s(o.getStatus()))
                .setCancelReason(s(o.getCancelReason()))
                .setParams(s(o.getParams()))
                .setUpdateTime(o.getUpdateTime() != null ? o.getUpdateTime().getTime() : 0)
                .setControlValue(o.getControlValue() != null ? o.getControlValue() : 0)
                .setUid(o.getUid() != null ? o.getUid() : 0)
                .build();
    }

    private static MatchOrderResult fromProtoOrder(PbMatchOrderResult pb) {
        MatchOrderResult o = new MatchOrderResult();
        o.setOrderId(pb.getOrderId());
        o.setAccountId(pb.getAccountId());
        o.setBusinessLine(toStr(pb.getBusinessLine()));
        o.setSecondBusinessLine(toStr(pb.getSecondBusinessLine()));
        o.setTokenId(toStr(pb.getTokenId()));
        o.setSymbolId(toStr(pb.getSymbolId()));
        o.setDealtCount(toBd(pb.getDealtCount()));
        o.setRemainCount(toBd(pb.getRemainCount()));
        o.setRemainAmount(toBd(pb.getRemainAmount()));
        o.setStatus(toStr(pb.getStatus()));
        o.setCancelReason(toStr(pb.getCancelReason()));
        o.setParams(toStr(pb.getParams()));
        o.setUpdateTime(pb.getUpdateTime() > 0 ? new Date(pb.getUpdateTime()) : null);
        o.setControlValue(pb.getControlValue());
        o.setUid(pb.getUid() != 0 ? pb.getUid() : null);
        return o;
    }

    // ==================== MatchCreateOrderResult ====================
    private static PbMatchCreateOrderResult toProtoCreate(MatchCreateOrderResult c) {
        return PbMatchCreateOrderResult.newBuilder()
                .setId(c.getId() != null ? c.getId() : 0)
                .setAccountId(c.getAccountId() != null ? c.getAccountId() : 0)
                .setSecondBusinessLine(s(c.getSecondBusinessLine()))
                .setTokenId(s(c.getTokenId()))
                .setSymbolId(s(c.getSymbolId()))
                .setDelegateType(s(c.getDelegateType()))
                .setOrderType(s(c.getOrderType()))
                .setControlValue(c.getControlValue() != null ? c.getControlValue() : 0)
                .setDelegateCount(bd(c.getDelegateCount()))
                .setDelegateAmount(bd(c.getDelegateAmount()))
                .setDelegatePrice(bd(c.getDelegatePrice()))
                .setDealtCount(bd(c.getDealtCount()))
                .setDealtAmount(bd(c.getDealtAmount()))
                .setAverageDealPrice(bd(c.getAverageDealPrice()))
                .setStatus(s(c.getStatus()))
                .setCancelReason(s(c.getCancelReason()))
                .setBusinessSource(s(c.getBusinessSource()))
                .setEnterPointSource(s(c.getEnterPointSource()))
                .setCreateTime(c.getCreateTime() != null ? c.getCreateTime().getTime() : 0)
                .setUpdateTime(c.getUpdateTime() != null ? c.getUpdateTime().getTime() : 0)
                .setParams(s(c.getParams()))
                .setVersion(c.getVersion() != null ? c.getVersion() : 0)
                .setUid(c.getUid() != null ? c.getUid() : 0)
                .build();
    }

    private static MatchCreateOrderResult fromProtoCreate(PbMatchCreateOrderResult pb) {
        MatchCreateOrderResult c = new MatchCreateOrderResult();
        c.setId(pb.getId());
        c.setAccountId(pb.getAccountId());
        c.setSecondBusinessLine(toStr(pb.getSecondBusinessLine()));
        c.setTokenId(toStr(pb.getTokenId()));
        c.setSymbolId(toStr(pb.getSymbolId()));
        c.setDelegateType(toStr(pb.getDelegateType()));
        c.setOrderType(toStr(pb.getOrderType()));
        c.setControlValue(pb.getControlValue());
        c.setDelegateCount(toBd(pb.getDelegateCount()));
        c.setDelegateAmount(toBd(pb.getDelegateAmount()));
        c.setDelegatePrice(toBd(pb.getDelegatePrice()));
        c.setDealtCount(toBd(pb.getDealtCount()));
        c.setDealtAmount(toBd(pb.getDealtAmount()));
        c.setAverageDealPrice(toBd(pb.getAverageDealPrice()));
        c.setStatus(toStr(pb.getStatus()));
        c.setCancelReason(toStr(pb.getCancelReason()));
        c.setBusinessSource(toStr(pb.getBusinessSource()));
        c.setEnterPointSource(toStr(pb.getEnterPointSource()));
        c.setCreateTime(pb.getCreateTime() > 0 ? new Date(pb.getCreateTime()) : null);
        c.setUpdateTime(pb.getUpdateTime() > 0 ? new Date(pb.getUpdateTime()) : null);
        c.setParams(toStr(pb.getParams()));
        c.setVersion(pb.getVersion());
        c.setUid(pb.getUid() != 0 ? pb.getUid() : null);
        return c;
    }
}