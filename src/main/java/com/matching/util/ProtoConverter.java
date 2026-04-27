package com.matching.util;

import com.matching.dto.*;
import com.matching.model.DepthUpdate;
import com.matching.proto.MatchingProto.*;
import com.matching.service.EventLog;

/**
 * Proto 转换门面类 - 委托给子模块，调用方零改动。
 * <p>
 * 对应关系说明：
 * - MatchResultProtoConverter - 撮合结果出口（DTO ↔ Proto）
 * - TradeCommandProtoConverter - 交易指令入口（Proto → DTO）
 * - EventLogProtoConverter - EventLog / Snapshot 持久化
 * - DepthProtoConverter - 深度推送
 */
public final class ProtoConverter {

    private ProtoConverter() {}

    // =============== MatchResult (出口) ===============
    public static PbMatchResult toProto(MatchResult r) {
        return MatchResultProtoConverter.toProto(r);
    }

    public static MatchResult fromProto(PbMatchResult pb) {
        return MatchResultProtoConverter.fromProto(pb);
    }

    public static byte[] serializeMatchResult(MatchResult r) {
        return MatchResultProtoConverter.serialize(r);
    }

    public static MatchResult deserializeMatchResult(byte[] data) throws Exception {
        return MatchResultProtoConverter.deserialize(data);
    }

    // =============== TradeCommand (入口) ===============
    public static PlaceOrderParam fromProto(PbPlaceOrderParam pb) {
        return TradeCommandProtoConverter.fromProto(pb);
    }

    public static PlaceMiniOrderParam fromProto(PbPlaceMiniOrderParam pb) {
        return TradeCommandProtoConverter.fromProto(pb);
    }

    public static CancelOrderParam fromProto(PbCancelOrderParam pb) {
        return TradeCommandProtoConverter.fromProto(pb);
    }

    // =============== EventLog / Snapshot (持久化) ===============
    public static PbEventLogEntry toProto(EventLog.Event event) {
        return EventLogProtoConverter.toProto(event);
    }

    public static EventLog.Event fromProto(PbEventLogEntry pb) {
        return EventLogProtoConverter.fromProto(pb);
    }

    public static byte[] serializeEvent(EventLog.Event event) {
        return EventLogProtoConverter.serializeEvent(event);
    }

    public static EventLog.Event deserializeEvent(byte[] data) throws Exception {
        return EventLogProtoConverter.deserializeEvent(data);
    }

    public static PbOrderBookEntry toProto(OrderBookEntry order) {
        return EventLogProtoConverter.toProto(order);
    }

    public static OrderBookEntry fromProto(PbOrderBookEntry pb) {
        return EventLogProtoConverter.fromProto(pb);
    }

    public static PbMatchResultEntry toProto(EventLog.MatchResultEntry result) {
        return EventLogProtoConverter.toProto(result);
    }

    public static EventLog.MatchResultEntry fromProto(PbMatchResultEntry pb) {
        return EventLogProtoConverter.fromProto(pb);
    }

    public static PbSnapshot toProto(EventLog.Snapshot snapshot) {
        return EventLogProtoConverter.toProto(snapshot);
    }

    public static EventLog.Snapshot fromProto(PbSnapshot pb) {
        return EventLogProtoConverter.fromProto(pb);
    }

    public static byte[] serializeSnapshot(EventLog.Snapshot snapshot) {
        return EventLogProtoConverter.serializeSnapshot(snapshot);
    }

    public static EventLog.Snapshot deserializeSnapshot(byte[] data) throws Exception {
        return EventLogProtoConverter.deserializeSnapshot(data);
    }

    // =============== Depth (撮合) ===============
    public static PbDepthUpdate convertDepthUpdate(DepthUpdate update) {
        return DepthProtoConverter.convertDepthUpdate(update);
    }

    public static PbPriceLevel convertPriceLevel(com.matching.model.PriceLevel level) {
        return DepthProtoConverter.convertPriceLevel(level);
    }
}