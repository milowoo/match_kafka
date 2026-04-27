package com.matching.command;

import com.matching.config.symbol.SymbolConfig;
import com.matching.config.symbol.SymbolConfigService;
import com.matching.constant.CancelReason;
import com.matching.engine.CompactOrderBookEntry;
import com.matching.engine.OrderList;
import com.matching.engine.PriceLevelBook;
import com.matching.service.OrderBookService;
import com.matching.util.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 撮合循环执行器：价格匹配、穿仓保护（滑点/档位）、自成交防护。
 */
@Component
@Slf4j
public class MatchExecutor {

    private final SymbolConfigService symbolConfigService;
    private final OrderBookService orderBookService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final MatchResultBuilder resultBuilder;

    public MatchExecutor(SymbolConfigService symbolConfigService,
                         OrderBookService orderBookService,
                         SnowflakeIdGenerator snowflakeIdGenerator,
                         MatchResultBuilder resultBuilder) {
        this.symbolConfigService = symbolConfigService;
        this.orderBookService = orderBookService;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.resultBuilder = resultBuilder;
    }

    /**
     * 检查吃单方订单是否会与对手方订单撮合
     *
     * @param taker 吃单方订单（买方或卖方）
     * @param oppositeBook 对手方订单簿
     * @param side 吃单方的买卖方向（BUY 或 SELL）
     * @return true 表示订单会立即撮合，false 表示不会撮合
     *         - 如果对手方订单簿为空，返回false
     *         - 买单时：价格 >= 卖方最优价格（最低卖价）则撮合
     *         - 卖单时：价格 <= 买方最优价格（最高买价）则撮合
     */
    public boolean wouldMatch(CompactOrderBookEntry taker, PriceLevelBook oppositeBook, byte side) {
        if (oppositeBook.isEmpty()) return false;
        long bestPrice = oppositeBook.bestPrice();
        return side == CompactOrderBookEntry.BUY ? taker.price >= bestPrice : taker.price <= bestPrice;
    }

    /**
     * 检查吃单方订单是否能完全成交
     *
     * @param taker 吃单方订单（买方或卖方）
     * @param oppositeBook 对手方订单簿
     * @param side 吃单方的买卖方向（BUY 或 SELL）
     * @return true 表示对手方订单簿中有足够的流动性完全成交吃单订单，false 表示流动性不足
     *
     * 算法说明：
     * 1. 遍历对手方订单簿的所有价格级别（按最优价格优先）
     * 2. 对于每个价格级别，累计可用成交数量
     * 3. 跳过与吃单方同账户的订单（防止自成交）
     * 4. 当累计数量 >= 吃单方所需数量时，返回true
     * 5. 对于限价单，会在价格不符合条件时停止查询
     */
    public boolean canFillEntirely(CompactOrderBookEntry taker, PriceLevelBook oppositeBook, byte side) {
        long remaining = taker.quantity;
        for (Map.Entry<Long, OrderList> levelEntry : oppositeBook.levels()) {
            long makerPrice = levelEntry.getKey();
            if (side == CompactOrderBookEntry.BUY && taker.price < makerPrice) break;
            if (side == CompactOrderBookEntry.SELL && taker.price > makerPrice) break;

            for (CompactOrderBookEntry maker : levelEntry.getValue()) {
                if (maker.accountId == taker.accountId) continue;
                remaining -= maker.remainingQty;
                if (remaining <= 0) return true;
            }
        }
        return remaining <= 0;
    }

    /**
     * 执行撮合循环 - 核心撮合逻辑，将吃单与对手方订单逐一配对成交
     *
     * @param ctx 撮合上下文，包含吃单订单、订单簿、撮合结果等信息
     *            - ctx.taker: 吃单方订单
     *            - ctx.side: 吃单方向（BUY/SELL）
     *            - ctx.matchEngine: 订单簿引擎
     *            - ctx.symbolId: 交易对ID
     *            - 返回结果写入ctx的以下字段：
     *              - lastTradePriceLong: 最后一笔成交价
     *              - totalDealtAmountLong: 累计成交金额
     *              - totalDealtCountLong: 累计成交数量
     *              - protectionCancelReason: 穿仓保护取消原因
     *              - removedOrderIds: 被完全成交移除的订单ID
     *              - changedLevels: 被撮合改变的价格级别
     *
     * 主要功能：
     * 1. 档位穿越保护：限制单笔订单能穿越的最大价格级别数，防止价格异常
     * 2. 滑点保护：限制成交价格相对于最优价格的偏离程度
     * 3. 自成交防护：跳过与吃单方同账户的卖单
     * 4. 冰山单处理：显示部分成交完后自动刷新隐藏部分
     * 5. 订单状态维护：跟踪新增订单、移除订单、改变的价格级别
     *
     * 撮合流程：
     * 1. 获取对手方订单簿
     * 2. 读取穿仓保护配置（最大档位、滑点限制）
     * 3. 逐个价格级别遍历对手方订单簿（按最优价格优先）
     * 4. 对每个价格级别内的订单进行逐一成交：
     *    - 跳过同账户订单（自成交防护）
     *    - 计算成交数量（取吃单剩余和卖单剩余的较小值）
     *    - 更新成交价、成交金额、成交数量
     *    - 构建成交记录和订单结果
     *    - 处理冰山单或完全成交的订单
     * 5. 更新订单簿中被改变的价格级别
     * 6. 更新交易对的最后成交价
     */
    public void execute(MatchContext ctx) {
        PriceLevelBook oppositeBook = ctx.side == CompactOrderBookEntry.BUY
                ? ctx.matchEngine.getSellBook() : ctx.matchEngine.getBuyBook();
        CompactOrderBookEntry taker = ctx.taker;

        // 穿仓保护参数
        SymbolConfig symbolConfig = symbolConfigService.getConfig(ctx.symbolId);
        int maxPriceLevels = symbolConfig != null ? symbolConfig.getMaxPriceLevels() : 0;
        long slippageThreshold = 0;
        if (symbolConfig != null && symbolConfig.getMaxSlippageRate() != null && !oppositeBook.isEmpty()) {
            long bestPrice = oppositeBook.bestPrice();
            slippageThreshold = Math.round(symbolConfig.getMaxSlippageRate().doubleValue() * bestPrice / CompactOrderBookEntry.PRECISION);
        }

        Set<Long> touchedPrices = new HashSet<>();
        int crossedLevels = 0;
        long triggerPrice = (slippageThreshold > 0 && !oppositeBook.isEmpty()) ? oppositeBook.bestPrice() : 0;

        Iterator<Map.Entry<Long, OrderList>> levelIt = oppositeBook.levels().iterator();
        while (levelIt.hasNext() && taker.remainingQty > 0) {
            Map.Entry<Long, OrderList> levelEntry = levelIt.next();
            long makerPriceLong = levelEntry.getKey();

            if (!ctx.isMarket()) {
                if (ctx.side == CompactOrderBookEntry.BUY && taker.price < makerPriceLong) break;
                if (ctx.side == CompactOrderBookEntry.SELL && taker.price > makerPriceLong) break;
            }

            // 档位穿越保护
            if (maxPriceLevels > 0 && ++crossedLevels > maxPriceLevels) {
                ctx.protectionCancelReason = CancelReason.MAX_LEVELS_EXCEEDED;
                log.warn("[{}] Max price levels exceeded: {}, orderId: {}", ctx.symbolId, maxPriceLevels, taker.orderId);
                break;
            }

            // 滑点保护
            if (slippageThreshold > 0 && triggerPrice > 0) {
                long deviation = Math.abs(makerPriceLong - triggerPrice);
                if (deviation > slippageThreshold) {
                    ctx.protectionCancelReason = CancelReason.SLIPPAGE_EXCEEDED;
                    log.warn("[{}] Slippage exceeded: deviation={}, threshold={}, orderId: {}",
                            ctx.symbolId, deviation, slippageThreshold, taker.orderId);
                    break;
                }
            }

            touchedPrices.add(makerPriceLong);
            OrderList orderList = levelEntry.getValue();

            Iterator<CompactOrderBookEntry> it = orderList.iterator();
            while (it.hasNext() && taker.remainingQty > 0) {
                CompactOrderBookEntry maker = it.next();

                // 自成交防护
                if (maker.accountId == taker.accountId) {
                    log.warn("Self-trade prevented, symbolId: {}, accountId: {}", ctx.symbolId, taker.accountId);
                    continue;
                }

                long fillQty = Math.min(taker.remainingQty, maker.remainingQty);
                taker.remainingQty -= fillQty;
                maker.remainingQty -= fillQty;

                Date now = new Date();
                long takerRecordId = snowflakeIdGenerator.nextId();
                long makerRecordId = snowflakeIdGenerator.nextId();
                // 使用long运算计算成交金额：price * qty / PRECISION
                long fillAmount = (makerPriceLong * fillQty) / CompactOrderBookEntry.PRECISION;

                ctx.lastTradePriceLong = makerPriceLong;
                ctx.totalDealtAmountLong += fillAmount;
                ctx.totalDealtCountLong += fillQty;

                resultBuilder.buildDealtRecords(ctx, maker, makerPriceLong, fillQty, fillAmount, takerRecordId, makerRecordId, now);
                resultBuilder.buildOrderResults(ctx, maker, fillQty, now);

                if (maker.isIceberg && maker.remainingQty <= 0 && maker.icebergHiddenQuantity > 0) {
                    // 冰山单显示部分被成交完，还有隐藏部分：从链表移除 -> 刷新 -> 重新挂单
                    it.remove();
                    ctx.matchEngine.refreshIcebergOrder(maker);
                    ctx.addedOrders.add(taker.toEntry(ctx.symbolId));
                    touchedPrices.add(makerPriceLong);
                } else if (maker.isFilled()) {
                    it.remove();
                    ctx.removedOrderIds.add(String.valueOf(maker.orderId));
                    orderBookService.removeOrder(ctx.symbolId, maker.orderId);
                }
            }

            if (orderList.isEmpty()) {
                levelIt.remove();
            }
        }

        if (!touchedPrices.isEmpty()) {
            Set<BigDecimal> touchedPricesBd = touchedPrices.stream()
                .map(CompactOrderBookEntry::toBigDecimal)
                .collect(java.util.stream.Collectors.toSet());
            ctx.changedLevels.computeIfAbsent(ctx.oppositeSide, k -> new HashSet<>()).addAll(touchedPricesBd);
        }

        // 更新最新成交价
        if (ctx.lastTradePriceLong > 0) {
            symbolConfigService.updateLastTradePrice(ctx.symbolId, 
                CompactOrderBookEntry.toBigDecimal(ctx.lastTradePriceLong));
        }
    }
}