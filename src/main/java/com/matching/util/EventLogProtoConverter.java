package com.matching.util;

import com.matching.dto.OrderBookEntry;
import com.matching.proto.MatchingProto.*;
import com.matching.service.EventLog;

import java.util.ArrayList;
import java.util.List;

import static com.matching.util.ProtoConvertUtil.*;

/**
 * EventLog / Snapshot / OrderBookEntry -> Proto 转换（持久化层）
 */
final class EventLogProtoConverter {

    private EventLogProtoConverter() {}

    // ====================== EventLog ======================
    static PbEventLogEntry toProto(EventLog.Event event) {
        PbEventLogEntry.Builder builder = PbEventLogEntry.newBuilder()
                .setSeq(event.getSeq())
                .setSymbolId(event.getSymbolId());

        if (event.getAddedOrders() != null) {
            for (OrderBookEntry order : event.getAddedOrders()) {
                builder.addAddedOrders(toProto(order));
            }
        }

        if (event.getRemovedOrderIds() != null) {
            builder.addAllRemovedOrderIds(event.getRemovedOrderIds());
        }

        if (event.getMatchResults() != null) {
            for (EventLog.MatchResultEntry result : event.getMatchResults()) {
                builder.addMatchResults(toProto(result));
            }
        }

        return builder.build();
    }

    static EventLog.Event fromProto(PbEventLogEntry pb) {
        EventLog.Event event = new EventLog.Event();
        event.setSeq(pb.getSeq());
        event.setSymbolId(pb.getSymbolId());

        if (pb.getAddedOrdersCount() > 0) {
            List<OrderBookEntry> addedOrders = new ArrayList<>();
            for (PbOrderBookEntry pbOrder : pb.getAddedOrdersList()) {
                addedOrders.add(fromProto(pbOrder));
            }
            event.setAddedOrders(addedOrders);
        }

        if (pb.getRemovedOrderIdsCount() > 0) {
            event.setRemovedOrderIds(new ArrayList<>(pb.getRemovedOrderIdsList()));
        }

        if (pb.getMatchResultsCount() > 0) {
            List<EventLog.MatchResultEntry> matchResults = new ArrayList<>();
            for (PbMatchResultEntry pbResult : pb.getMatchResultsList()) {
                matchResults.add(fromProto(pbResult));
            }
            event.setMatchResults(matchResults);
        }

        return event;
    }

    static byte[] serializeEvent(EventLog.Event event) {
        return toProto(event).toByteArray();
    }

    static EventLog.Event deserializeEvent(byte[] data) throws Exception {
        return fromProto(PbEventLogEntry.parseFrom(data));
    }

    // ====================== Snapshot ======================
    static PbSnapshot toProto(EventLog.Snapshot snapshot) {
        PbSnapshot.Builder builder = PbSnapshot.newBuilder()
                .setSeq(snapshot.getSeq())
                .setSymbolId(snapshot.getSymbolId());

        if (snapshot.getAllOrders() != null) {
            for (OrderBookEntry order : snapshot.getAllOrders()) {
                builder.addAllOrders(toProto(order));
            }
        }

        return builder.build();
    }

    static EventLog.Snapshot fromProto(PbSnapshot pb) {
        List<OrderBookEntry> orders = new ArrayList<>();
        for (PbOrderBookEntry pbOrder : pb.getAllOrdersList()) {
            orders.add(fromProto(pbOrder));
        }
        return new EventLog.Snapshot(pb.getSeq(), pb.getSymbolId(), orders);
    }

    static byte[] serializeSnapshot(EventLog.Snapshot snapshot) {
        return toProto(snapshot).toByteArray();
    }

    static EventLog.Snapshot deserializeSnapshot(byte[] data) throws Exception {
        return fromProto(PbSnapshot.parseFrom(data));
    }

    // ====================== OrderBookEntry ======================
    static PbOrderBookEntry toProto(OrderBookEntry order) {
        PbOrderBookEntry.Builder b = PbOrderBookEntry.newBuilder()
                .setClientOrderId(order.getClientOrderId())
                .setAccountId(order.getAccountId() != null ? order.getAccountId() : 0)
                .setSymbolId(order.getSymbolId())
                .setSide(order.getSide())
                .setPrice(bd(order.getPrice()))
                .setQuantity(bd(order.getQuantity()))
                .setRemainingQuantity(bd(order.getRemainingQuantity()))
                .setRequestTime(order.getRequestTime())
                .setVip(order.isVip())
                .setIsIceberg(order.isIceberg());

        if (order.isIceberg()) {
            b.setIcebergTotalQuantity(bd(order.getIcebergTotalQuantity()))
                    .setIcebergDisplayQuantity(bd(order.getIcebergDisplayQuantity()))
                    .setIcebergHiddenQuantity(bd(order.getIcebergHiddenQuantity()))
                    .setIcebergRefreshQuantity(bd(order.getIcebergRefreshQuantity()));
        }

        return b.build();
    }

    static OrderBookEntry fromProto(PbOrderBookEntry pb) {
        OrderBookEntry.OrderBookEntryBuilder b = OrderBookEntry.builder()
                .clientOrderId(toStr(pb.getClientOrderId()))
                .accountId(pb.getAccountId())
                .symbolId(toStr(pb.getSymbolId()))
                .side(toStr(pb.getSide()))
                .price(toBd(pb.getPrice()))
                .quantity(toBd(pb.getQuantity()))
                .remainingQuantity(toBd(pb.getRemainingQuantity()))
                .requestTime(pb.getRequestTime())
                .vip(pb.getVip())
                .isIceberg(pb.getIsIceberg());

        if (pb.getIsIceberg()) {
            b.icebergTotalQuantity(toBd(pb.getIcebergTotalQuantity()))
                    .icebergDisplayQuantity(toBd(pb.getIcebergDisplayQuantity()))
                    .icebergHiddenQuantity(toBd(pb.getIcebergHiddenQuantity()))
                    .icebergRefreshQuantity(toBd(pb.getIcebergRefreshQuantity()));
        }

        return b.build();
    }

    // ====================== MatchResultEntry ======================
    static PbMatchResultEntry toProto(EventLog.MatchResultEntry result) {
        PbMatchResultEntry.Builder builder = PbMatchResultEntry.newBuilder()
                .setAccountKey(result.getUidKey());

        if (result.getPayload() != null && result.getPayload().length > 0) {
            builder.setPayload(com.google.protobuf.ByteString.copyFrom(result.getPayload()));
        }

        return builder.build();
    }

    static EventLog.MatchResultEntry fromProto(PbMatchResultEntry pb) {
        EventLog.MatchResultEntry result = new EventLog.MatchResultEntry();
        result.setUidKey(pb.getAccountKey());
        if (!pb.getPayload().isEmpty()) {
            result.setPayload(pb.getPayload().toByteArray());
        }
        return result;
    }
}