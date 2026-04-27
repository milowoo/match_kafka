package com.matching.service;

import com.matching.config.symbol.SymbolConfig;
import com.matching.config.symbol.SymbolConfigService;
import com.matching.enums.OrderType;
import com.matching.constant.RejectReason;
import com.matching.dto.PlaceMiniOrderParam;
import com.matching.dto.PlaceOrderParam;
import com.matching.engine.MatchEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@Slf4j
public class OrderValidator {

    private final SymbolConfigService symbolConfigService;
    private final OrderBookService orderBookService;
    private final RateLimiter rateLimiter;

    public OrderValidator(SymbolConfigService symbolConfigService,
                           OrderBookService orderBookService,
                           RateLimiter rateLimiter) {
        this.symbolConfigService = symbolConfigService;
        this.orderBookService = orderBookService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Returns: null if valid, error message if invalid
     */
    public String validate(PlaceOrderParam message) {
        // uid 必填，用于 Kafka 消息路由
        if (message.getUid() == null || message.getUid() <= 0) {
            return RejectReason.UID_REQUIRED;
        }

        String symbolId = message.getSymbolId();
        SymbolConfig config = symbolConfigService.getConfig(symbolId);

        if (config == null) {
            return RejectReason.SYMBOL_NOT_CONFIGURED + ": " + symbolId;
        }

        if (!config.isTradingEnabled()) {
            return RejectReason.TRADING_DISABLED + ": " + symbolId;
        }

        if (message.getOrderList() == null || message.getOrderList().isEmpty()) {
            return RejectReason.EMPTY_ORDER_LIST;
        }

        // Rate limit
        String rateError = rateLimiter.check(message.getAccountId(), symbolId);
        if (rateError != null) return rateError;

        PlaceMiniOrderParam order = message.getOrderList().get(0);
        OrderType orderType = parseOrderType(order.getOrderType());

        // Max open orders (only for orders that will rest on book)
        if (orderType == OrderType.LIMIT || orderType == OrderType.LIMIT_POST_ONLY) {
            String openOrderError = validateMaxOpenOrders(message.getAccountId(), symbolId, config);
            if (openOrderError != null) return openOrderError;
        }

        // Market order: only validate quantity
        if (orderType == OrderType.MARKET) {
            return validateQuantity(order.getDelegateCount(), config);
        }

        // Limit orders: validate price + quantity + notional + deviation
        String qtyError = validateQuantity(order.getDelegateCount(), config);
        if (qtyError != null) return qtyError;

        String priceError = validatePrice(order.getDelegatePrice(), config);
        if (priceError != null) return priceError;

        String precisionError = validatePrecision(order.getDelegatePrice(), order.getDelegateCount(), config);
        if (precisionError != null) return precisionError;

        String notionalError = validateNotional(order.getDelegatePrice(), order.getDelegateCount(), config);
        if (notionalError != null) return notionalError;

        String deviationError = validatePriceDeviation(symbolId, order.getDelegatePrice(), config);
        if (deviationError != null) return deviationError;

        // 验证冰山单参数
        String icebergError = validateIcebergOrder(order, config);
        if (icebergError != null) return icebergError;

        return null;
    }

    private String validateMaxOpenOrders(long accountId, String symbolId, SymbolConfig config) {
        if (config.getMaxOpenOrders() <= 0) return null;

        MatchEngine engine = orderBookService.getEngine(symbolId);
        int count = engine.getAccountOrderCount(accountId);

        if (count >= config.getMaxOpenOrders()) {
            return RejectReason.MAX_OPEN_ORDERS_REACHED + ": " + config.getMaxOpenOrders() + ", account: " + accountId;
        }
        return null;
    }

    private String validateQuantity(BigDecimal quantity, SymbolConfig config) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return RejectReason.QUANTITY_NOT_POSITIVE;
        }
        if (quantity.compareTo(config.getMinQuantity()) < 0) {
            return RejectReason.QUANTITY_BELOW_MIN + ": " + quantity.toPlainString() + ", min: " + config.getMinQuantity().toPlainString();
        }
        if (quantity.compareTo(config.getMaxQuantity()) > 0) {
            return RejectReason.QUANTITY_EXCEED_MAX + ": " + quantity.toPlainString() + ", max: " + config.getMaxQuantity().toPlainString();
        }
        return null;
    }

    private String validatePrice(BigDecimal price, SymbolConfig config) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return RejectReason.PRICE_NOT_POSITIVE;
        }
        if (price.compareTo(config.getMinPrice()) < 0) {
            return RejectReason.PRICE_BELOW_MIN + ": " + price.toPlainString() + ", min: " + config.getMinPrice().toPlainString();
        }
        if (price.compareTo(config.getMaxPrice()) > 0) {
            return RejectReason.PRICE_EXCEED_MAX + ": " + price.toPlainString() + ", max: " + config.getMaxPrice().toPlainString();
        }
        return null;
    }

    private String validatePrecision(BigDecimal price, BigDecimal quantity, SymbolConfig config) {
        if (price != null && price.stripTrailingZeros().scale() > config.getPricePrecision()) {
            return RejectReason.PRICE_PRECISION_EXCEED + ": " + config.getPricePrecision();
        }
        if (quantity != null && quantity.stripTrailingZeros().scale() > config.getQuantityPrecision()) {
            return RejectReason.QUANTITY_PRECISION_EXCEED + ": " + config.getQuantityPrecision();
        }
        return null;
    }

    private String validateNotional(BigDecimal price, BigDecimal quantity, SymbolConfig config) {
        if (price == null || quantity == null) return null;
        BigDecimal notional = price.multiply(quantity);
        if (config.getMinNotional() != null && notional.compareTo(config.getMinNotional()) < 0) {
            return RejectReason.NOTIONAL_BELOW_MIN + ": " + notional.toPlainString() + ", min: " + config.getMinNotional().toPlainString();
        }
        return null;
    }

    private String validatePriceDeviation(String symbolId, BigDecimal price, SymbolConfig config) {
        if (config.getPriceDeviationRate() == null || price == null) return null;
        BigDecimal lastPrice = symbolConfigService.getLastTradePrice(symbolId);
        if (lastPrice == null || lastPrice.compareTo(BigDecimal.ZERO) <= 0) return null;

        BigDecimal deviation = price.subtract(lastPrice).abs()
                .divide(lastPrice, 4, RoundingMode.HALF_UP);

        if (deviation.compareTo(config.getPriceDeviationRate()) > 0) {
            return RejectReason.PRICE_DEVIATION_EXCEED + ": " + deviation.toPlainString()
                    + ", limit: " + config.getPriceDeviationRate().toPlainString() + ", lastPrice: " + lastPrice.toPlainString();
        }
        return null;
    }

    private OrderType parseOrderType(String orderType) {
        if (orderType == null) return OrderType.LIMIT;
        try {
            return OrderType.valueOf(orderType.toUpperCase());
        } catch (IllegalArgumentException e) {
            return OrderType.LIMIT;
        }
    }

    private String validateIcebergOrder(PlaceMiniOrderParam order, SymbolConfig config) {
        if (!order.isIceberg()) {
            return null; // 不是冰山单，无需验证
        }

        // 验证冰山单总量
        if (order.getIcebergTotalQuantity() == null || order.getIcebergTotalQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return RejectReason.ICEBERG_TOTAL_QUANTITY_INVALID;
        }

        // 验证显示量
        if (order.getIcebergDisplayQuantity() == null || order.getIcebergDisplayQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return RejectReason.ICEBERG_DISPLAY_QUANTITY_INVALID;
        }

        // 验证刷新量
        if (order.getIcebergRefreshQuantity() == null || order.getIcebergRefreshQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return RejectReason.ICEBERG_REFRESH_QUANTITY_INVALID;
        }

        // 验证显示量不能大于总量
        if (order.getIcebergDisplayQuantity().compareTo(order.getIcebergTotalQuantity()) > 0) {
            return RejectReason.ICEBERG_DISPLAY_EXCEED_TOTAL;
        }

        // 验证刷新量不能大于总量
        if (order.getIcebergRefreshQuantity().compareTo(order.getIcebergTotalQuantity()) > 0) {
            return RejectReason.ICEBERG_REFRESH_EXCEED_TOTAL;
        }

        // 验证冰山单总量与订单量一致
        if (order.getDelegateCount() != null && order.getIcebergTotalQuantity().compareTo(order.getDelegateCount()) != 0) {
            return RejectReason.ICEBERG_TOTAL_QUANTITY_MISMATCH;
        }

        // 验证冰山单最小显示量限制
        BigDecimal minIcebergDisplay = config.getMinQuantity().multiply(BigDecimal.valueOf(2)); // 最小显示量为最小订单量的2倍
        if (order.getIcebergDisplayQuantity().compareTo(minIcebergDisplay) < 0) {
            return RejectReason.ICEBERG_DISPLAY_BELOW_MIN + ": " + minIcebergDisplay.toPlainString();
        }

        return null;
    }
}