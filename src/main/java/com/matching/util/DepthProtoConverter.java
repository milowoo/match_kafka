package com.matching.util;

import com.matching.model.DepthUpdate;
import com.matching.model.PriceLevel;
import com.matching.proto.MatchingProto.*;
import static com.matching.util.ProtoConvertUtil.*;

/**
 * 深度数据 -> Proto 转换（推送给行情服务）
 */
final class DepthProtoConverter {

    private DepthProtoConverter() {}

    static PbPriceLevel convertPriceLevel(PriceLevel level) {
        return PbPriceLevel.newBuilder()
                .setPrice(String.valueOf(level.getPrice()))
                .setQuantity(String.valueOf(level.getQuantity()))
                .build();
    }

    static PbDepthUpdate convertDepthUpdate(DepthUpdate update) {
        PbDepthUpdate.Builder builder = PbDepthUpdate.newBuilder()
                .setSymbolId(update.getSymbol())
                .setType(update.isSnapshot() ? "SNAPSHOT" : "INCREMENTAL")
                .setTimestamp(update.getTimestamp());

        if (update.getBids() != null) {
            for (PriceLevel level : update.getBids()) {
                builder.addBids(convertPriceLevel(level));
            }
        }

        if (update.getAsks() != null) {
            for (PriceLevel level : update.getAsks()) {
                builder.addAsks(convertPriceLevel(level));
            }
        }

        return builder.build();
    }

}