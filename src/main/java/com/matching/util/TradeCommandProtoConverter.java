package com.matching.util;

import com.matching.dto.*;
import com.matching.proto.MatchingProto.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import static com.matching.util.ProtoConvertUtil.*;

/**
 * 交易指令 Proto → DTO 转换（入口：Kafka 消费端）
 */
final class TradeCommandProtoConverter {

    private TradeCommandProtoConverter() {}

    static PlaceOrderParam fromProto(PbPlaceOrderParam pb) {
        PlaceOrderParam p = new PlaceOrderParam();
        p.setRequestTime(pb.getRequestTime());
        p.setBusinessLine(toStr(pb.getBusinessLine()));
        p.setSecondBusinessLine(toStr(pb.getSecondBusinessLine()));
        p.setAccountId(pb.getAccountId() != 0 ? pb.getAccountId() : null);
        p.setTokenId(toStr(pb.getTokenId()));
        p.setSymbolId(toStr(pb.getSymbolId()));
        p.setOpenPrice(toBd(pb.getOpenPrice()));
        p.setSymbolOpen(pb.getSymbolOpen());
        p.setUid(pb.getUid() != 0 ? pb.getUid() : null);

        if (pb.getOrderListCount() > 0) {
            List<PlaceMiniOrderParam> list = new ArrayList<>(pb.getOrderListCount());
            for (PbPlaceMiniOrderParam m : pb.getOrderListList()) {
                list.add(fromProto(m));
            }
            p.setOrderList(list);
        }
        return p;
    }

    static PlaceMiniOrderParam fromProto(PbPlaceMiniOrderParam pb) {
        PlaceMiniOrderParam m = new PlaceMiniOrderParam();
        m.setOrderNo(pb.getOrderNo() != 0 ? pb.getOrderNo() : null);
        m.setDelegateType(toStr(pb.getDelegateType()));
        m.setOrderType(toStr(pb.getOrderType()));
        m.setDelegateCount(toBd(pb.getDelegateCount()));
        m.setDelegateAmount(toBd(pb.getDelegateAmount()));
        m.setDelegatePrice(toBd(pb.getDelegatePrice()));
        m.setControlValue(pb.getControlValue());
        m.setBusinessSource(toStr(pb.getBusinessSource()));
        m.setEnterPointSource(toStr(pb.getEnterPointSource()));
        m.setCreateTime(pb.getCreateTime() > 0 ? new Date(pb.getCreateTime()) : null);
        m.setParams(toStr(pb.getParams()));
        m.setDelegateLeverage(toBd(pb.getDelegateLeverage()));
        m.setCancelReason(toStr(pb.getCancelReason()));
        m.setVip(pb.getVip());
        m.setIceberg(pb.getIsIceberg());
        m.setIcebergTotalQuantity(toBd(pb.getIcebergTotalQuantity()));
        m.setIcebergDisplayQuantity(toBd(pb.getIcebergDisplayQuantity()));
        m.setIcebergRefreshQuantity(toBd(pb.getIcebergRefreshQuantity()));
        return m;
    }

    static CancelOrderParam fromProto(PbCancelOrderParam pb) {
        return CancelOrderParam.builder()
                .symbolId(toStr(pb.getSymbolId()))
                .orderIds(pb.getOrderIdsList().isEmpty() ? null : new ArrayList<>(pb.getOrderIdsList()))
                .accountId(pb.getAccountId() != 0 ? pb.getAccountId() : null)
                .cancelAll(pb.getCancelAll())
                .uid(pb.getUid() != 0 ? pb.getUid() : null)
                .build();
    }
}