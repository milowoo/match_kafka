package com.matching.command;

import com.matching.constant.CancelReason;
import com.matching.constant.OrderStatus;
import com.matching.enums.OrderType;
import com.matching.dto.*;
import com.matching.engine.CompactOrderBookEntry;
import com.matching.engine.MatchEngine;
import com.matching.engine.PriceLevelBook;
import com.matching.service.HAService;
import com.matching.service.OrderBookService;
import com.matching.service.OrderValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Slf4j
public class PlaceOrderCommand {

    private final OrderBookService orderBookService;
    private final OrderValidator orderValidator;
    private final TakerOrderFactory takerOrderFactory;
    private final MatchExecutor matchExecutor;
    private final MatchResultBuilder resultBuilder;
    private final HAService haService;

    // For testing
    private MatchEngine engine;

    public PlaceOrderCommand(OrderBookService orderBookService,
                             OrderValidator orderValidator,
                             TakerOrderFactory takerOrderFactory,
                             MatchExecutor matchExecutor,
                             MatchResultBuilder resultBuilder,
                             HAService haService) {
        this.orderBookService = orderBookService;
        this.orderValidator = orderValidator;
        this.takerOrderFactory = takerOrderFactory;
        this.matchExecutor = matchExecutor;
        this.resultBuilder = resultBuilder;
        this.haService = haService;
    }

    // For testing
    public void setEngine(MatchEngine engine) {
        this.engine = engine;
    }

    /**
     * 执行下单命令 - 处理限价单、市价单、冰山单等各类订单的完整流程
     *
     * @param message 下单参数，包含订单基本信息、账户信息、交易对等
     * @return 命令执行结果，包含成交结果、订单状态、订单簿变化等
     *
     * 执行流程（7个步骤）：
     * 1. HA角色检查：确保只在PRIMARY角色处理订单
     * 2. 订单簿存在性检查：验证交易对订单簿已初始化
     * 3. 订单校验：检查价格、数量、账户权限等
     * 4. 构建撮合上下文：准备撮合所需的数据结构
     * 5. 前置检查：处理特殊订单类型（POST_ONLY、FOK等）
     * 6. 撮合循环：执行吃单与卖单的配对成交
     * 7. 剩余处理：处理未成交部分（上架、取消或保留）
     *
     * 特殊订单类型处理：
     * - POST_ONLY: 只能作为挂单方，不能立即成交
     * - FOK (Fill or Kill): 全部成交或全部取消，不接受部分成交
     * - IOC (Immediate or Cancel): 立即成交或取消，剩余部分被取消
     * - MARKET: 市价单，按对手方最优价格成交，无法完全成交则取消剩余
     * - LIMIT: 限价单，可以部分成交后上架
     * - ICEBERG: 冰山单，显示部分成交完后自动刷新隐藏部分
     *
     * @throws 无异常，所有异常都转换为拒绝结果返回
     */
    public CommandResult execute(PlaceOrderParam message) {
        String symbolId = message.getSymbolId();

        // 检查是否在PRIMARY角色，STANDBY角色不应该处理订单
        if (!haService.isActive()) {
            log.warn("Not in PRIMARY role, rejecting order for symbolId: {}, currentRole: {}", 
                    symbolId, haService.getCurrentRole());
            return resultBuilder.buildRejectedResult(message, "System not ready - not in PRIMARY role");
        }

        if (!orderBookService.exists(symbolId)) {
            log.error("OrderBook not initialized for symbolId: {}, system state may be inconsistent. " +
                    "This should not happen as symbols should be loaded during HA activation.", symbolId);
            return resultBuilder.buildRejectedResult(message, "OrderBook not initialized - contact support");
        }

        // 1. 校验
        String error = orderValidator.validate(message);
        if (error != null) {
            log.warn("Order rejected for symbolId: {}, reason: {}", symbolId, error);
            return resultBuilder.buildRejectedResult(message, error);
        }

        // 2. 构建上下文
        PlaceMiniOrderParam miniOrder = message.getOrderList().get(0);
        OrderType orderType = parseOrderType(miniOrder.getOrderType());
        MatchEngine matchEngine = this.engine != null ? this.engine : orderBookService.getEngine(symbolId);
        MatchContext ctx = new MatchContext(symbolId, message, miniOrder, orderType, matchEngine);

        ctx.createOrder = resultBuilder.buildCreateOrder(message, miniOrder);
        ctx.matchResult.getCreateOrders().add(ctx.createOrder);

        // 3. 构建 taker
        ctx.taker = takerOrderFactory.create(ctx);

        PriceLevelBook oppositeBook = ctx.side == CompactOrderBookEntry.BUY
                ? matchEngine.getSellBook() : matchEngine.getBuyBook();

        // 4. 前置检查
        if (ctx.isPostOnly() && matchExecutor.wouldMatch(ctx.taker, oppositeBook, ctx.side)) {
            matchEngine.releaseEntry(ctx.taker);
            return resultBuilder.buildRejectedResult(message, CancelReason.POST_ONLY_WOULD_MATCH);
        }

        //如果是 FOK 订单类型， 如果没有足够的订单匹配， 直接拒绝返回
        if (ctx.isFOK() && matchExecutor.canFillEntirely(ctx.taker, oppositeBook, ctx.side)) {
            matchEngine.releaseEntry(ctx.taker);
            return resultBuilder.buildRejectedResult(message, CancelReason.FOK_CANNOT_FILL);
        }

        // 5. 撮合循环
        matchExecutor.execute(ctx);

        // 6. 更新成交汇总
        resultBuilder.updateCreateOrderDealtSummary(ctx.createOrder, 
            CompactOrderBookEntry.toBigDecimal(ctx.totalDealtAmountLong), 
            CompactOrderBookEntry.toBigDecimal(ctx.totalDealtCountLong));

        // 7. 处理剩余数量
        handleRemaining(ctx);

        return new CommandResult(ctx.matchResult,
                new SyncPayload(symbolId, ctx.changedLevels, ctx.addedOrders, ctx.removedOrderIds));
    }

    /**
     * 处理订单的剩余数量 - 决定剩余部分是否上架、取消还是保留
     *
     * @param ctx 撮合上下文，包含订单信息、撮合结果、剩余数量等
     *
     * 处理逻辑：
     * 1. 如果有剩余数量 (remainingQty > 0)：
     *    a) 触发穿仓保护、市价单、IOC、FOK 等特殊情况 → 取消剩余
     *    b) 其他情况（限价单） → 剩余部分上架（挂单）
     * 2. 如果无剩余数量 (remainingQty == 0)：
     *    a) 冰山单且有隐藏部分 → 刷新后重新挂单，标记为部分成交
     *    b) 普通订单 → 完全成交，标记为FILLED并释放内存
     *
     * 关键状态：
     * - FILLED: 订单完全成交
     * - PARTIAL_FILLED: 订单部分成交（上架或取消剩余时）
     * - CANCELLED: 订单被取消（穿仓保护或订单簿满时）
     */
    private void handleRemaining(MatchContext ctx) {
        CompactOrderBookEntry taker = ctx.taker;
        MatchEngine matchEngine = ctx.matchEngine;
        MatchCreateOrderResult createOrder = ctx.createOrder;

        if (taker.remainingQty > 0) {
            if (ctx.protectionCancelReason != null || ctx.isMarket() || ctx.isIOC() || ctx.isFOK()) {
                cancelRemaining(ctx);
            } else {
                restOnBook(ctx);
            }
        } else {
            if (taker.isIceberg && taker.icebergHiddenQuantity > 0) {
                // taker 显示部分被完全成交，刷新显示量后挂单
                matchEngine.refreshIcebergOrder(taker);
                createOrder.setStatus(OrderStatus.PARTIAL_FILLED);
                createOrder.setDealtCount(CompactOrderBookEntry.toBigDecimal(taker.quantity - taker.remainingQty));
                ctx.changedLevels.computeIfAbsent(ctx.sideStr, k -> new HashSet<>())
                        .add(CompactOrderBookEntry.toBigDecimal(taker.price));
                ctx.addedOrders.add(taker.toEntry(ctx.symbolId));
            } else {
                createOrder.setStatus(OrderStatus.FILLED);
                createOrder.setDealtCount(CompactOrderBookEntry.toBigDecimal(taker.quantity));
                matchEngine.releaseEntry(taker);
            }
        }
    }

    /**
     * 取消订单的剩余部分 - 生成取消记录并更新订单状态
     *
     * @param ctx 撮合上下文
     *
     * 取消原因分类：
     * - 穿仓保护被触发 (MAX_LEVELS_EXCEEDED/SLIPPAGE_EXCEEDED)
     * - 市价单无充足流动性 (MARKET_NO_LIQUIDITY)
     * - IOC订单有部分未成交 (IOC_UNFILLED)
     * - FOK订单无法全部成交 (FOK_PARTIAL)
     *
     * 处理步骤：
     * 1. 确定取消原因
     * 2. 更新创建订单状态：
     *    - 如果完全未成交 → CANCELLED
     *    - 如果有部分成交 → PARTIAL_FILLED
     * 3. 生成成交记录（用于数据库记录）
     * 4. 释放订单内存
     */
    private void cancelRemaining(MatchContext ctx) {
        CompactOrderBookEntry taker = ctx.taker;
        MatchCreateOrderResult createOrder = ctx.createOrder;

        String reason = ctx.protectionCancelReason != null ? ctx.protectionCancelReason
                : ctx.isMarket() ? CancelReason.MARKET_NO_LIQUIDITY
                : ctx.isIOC() ? CancelReason.IOC_UNFILLED : CancelReason.FOK_PARTIAL;

        createOrder.setStatus(taker.remainingQty == taker.quantity ? OrderStatus.CANCELLED : OrderStatus.PARTIAL_FILLED);
        createOrder.setDealtCount(CompactOrderBookEntry.toBigDecimal(taker.quantity - taker.remainingQty));
        createOrder.setCancelReason(reason);

        MatchOrderResult cancelResult = new MatchOrderResult();
        cancelResult.setOrderId(ctx.miniOrder.getOrderNo());
        cancelResult.setAccountId(ctx.message.getAccountId());
        cancelResult.setSymbolId(ctx.symbolId);
        cancelResult.setDealtCount(CompactOrderBookEntry.toBigDecimal(taker.quantity - taker.remainingQty));
        cancelResult.setRemainCount(CompactOrderBookEntry.toBigDecimal(taker.remainingQty));
        cancelResult.setStatus(OrderStatus.CANCELLED);
        cancelResult.setCancelReason(reason);
        cancelResult.setUpdateTime(new Date());
        ctx.matchResult.getDealtOrders().add(cancelResult);

        ctx.matchEngine.releaseEntry(taker);
    }

    /**
     * 将订单剩余部分上架至订单簿 - 使订单成为挂单方等待撮合
     *
     * @param ctx 撮合上下文
     *
     * 处理逻辑：
     * 1. 尝试将订单加入订单簿
     * 2. 如果订单簿满 (addOrder返回false)：
     *    a) 已有成交 → 保留成交记录，标记为PARTIAL_FILLED，取消剩余，原因为ORDER_BOOK_FULL
     *    b) 无成交 → 整个订单被拒绝，标记为REJECTED
     * 3. 如果上架成功：
     *    a) 更新创建订单状态（PENDING或PARTIAL_FILLED）
     *    b) 更新价格级别变化记录（用于推送行情）
     *    c) 添加至新增订单列表（用于同步订单簿）
     *
     * 异常处理：
     * - 订单簿满是正常的容量保护机制，不是错误
     * - 部分成交后订单簿满时，保留已成交的记录
     *
     * 副作用（修改ctx的以下字段）：
     * - ctx.createOrder 的状态和成交数量
     * - ctx.changedLevels：记录变化的价格级别
     * - ctx.addedOrders：记录新增的订单
     */
    private void restOnBook(MatchContext ctx) {
        CompactOrderBookEntry taker = ctx.taker;
        if (!ctx.matchEngine.addOrder(taker)) {
            ctx.matchEngine.releaseEntry(taker);
            // 订单簿满：如果已有成交，保留成交记录，标记为部分成交+取消剩余
            // 如果无成交，标记为拒绝
            MatchCreateOrderResult createOrder = ctx.createOrder;
            if (!ctx.matchResult.getDealtRecords().isEmpty()) {
                createOrder.setStatus(OrderStatus.PARTIAL_FILLED);
                createOrder.setDealtCount(CompactOrderBookEntry.toBigDecimal(ctx.totalDealtCountLong));
                createOrder.setDealtAmount(CompactOrderBookEntry.toBigDecimal(ctx.totalDealtAmountLong));
                createOrder.setCancelReason(CancelReason.ORDER_BOOK_FULL);

                MatchOrderResult cancelResult = new MatchOrderResult();
                cancelResult.setOrderId(ctx.miniOrder.getOrderNo());
                cancelResult.setAccountId(ctx.message.getAccountId());
                cancelResult.setSymbolId(ctx.symbolId);
                cancelResult.setDealtCount(CompactOrderBookEntry.toBigDecimal(ctx.totalDealtCountLong));
                cancelResult.setRemainCount(CompactOrderBookEntry.toBigDecimal(taker.remainingQty));
                cancelResult.setStatus(OrderStatus.CANCELLED);
                cancelResult.setCancelReason(CancelReason.ORDER_BOOK_FULL);
                cancelResult.setUpdateTime(new Date());
                ctx.matchResult.getDealtOrders().add(cancelResult);
            } else {
                CommandResult rejected = resultBuilder.buildRejectedResult(ctx.message, CancelReason.ORDER_BOOK_FULL);
                ctx.matchResult.getCreateOrders().addAll(rejected.getMatchResult().getCreateOrders());
            }
            return;
        }

        resultBuilder.updateCreateOrderStatus(ctx.createOrder, taker);
        ctx.changedLevels.computeIfAbsent(ctx.sideStr, k -> new HashSet<>())
                .add(CompactOrderBookEntry.toBigDecimal(taker.price));
        ctx.addedOrders.add(taker.toEntry(ctx.symbolId));
    }

    /**
     * 解析订单类型字符串为枚举值
     *
     * @param orderType 订单类型字符串（如"LIMIT"、"MARKET"等），为null或无效值时默认为LIMIT
     * @return 解析后的OrderType枚举值
     *
     * 支持的订单类型：
     * - LIMIT: 限价单，按指定价格或更优价格成交
     * - MARKET: 市价单，按市场最优价格立即成交
     * - IOC: Immediate or Cancel，立即成交或取消未成交部分
     * - FOK: Fill or Kill，全部成交或全部取消
     * - POST_ONLY: 只允许作为挂单方，不能立即成交
     * - ICEBERG: 冰山单，显示部分和隐藏部分
     *
     * 容错处理：
     * - 如果orderType为null，返回LIMIT（默认值）
     * - 如果无法识别，返回LIMIT（默认值）
     * - 字符串匹配不区分大小写
     */
    private OrderType parseOrderType(String orderType) {
        if (orderType == null) return OrderType.LIMIT;
        try {
            return OrderType.valueOf(orderType.toUpperCase());
        } catch (IllegalArgumentException e) {
            return OrderType.LIMIT;
        }
    }
}