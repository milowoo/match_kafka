package com.matching.service.depth;

import com.matching.model.DepthUpdate;
import com.matching.model.OrderBook;
import com.matching.model.PriceLevel;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 深度数据构建器 - 负责构建各种类型的深度更新
 */
@Component
@Slf4j
public class DepthDataBuilder {

    // 对象池
    private final ThreadLocal<FastList<PriceLevel>> priceLevelPool =
            ThreadLocal.withInitial(FastList::new);

    /**
     * 从MatchEngine构建OrderBook
     */
    public OrderBook buildOrderBookFromEngine(com.matching.engine.MatchEngine engine) {
        OrderBook orderBook = new OrderBook();

        try {
            // 构建买单深度
            if (engine.getBuyBook() != null) {
                engine.getBuyBook().levels().forEach(entry -> {
                    if (entry != null && entry.getKey() != null && entry.getValue() != null) {
                        long totalQty = 0;
                        try {
                            for (com.matching.engine.CompactOrderBookEntry order : entry.getValue()) {
                                if (order != null) {
                                    totalQty += order.remainingQty;
                                }
                            }
                        } catch (Exception e) {
                            // 并发修改异常，忽略该价格档位
                            return;
                        }
                        if (totalQty > 0) {
                            orderBook.addBuyOrder(entry.getKey(), totalQty);
                        }
                    }
                });
            }

            // 构建卖单深度
            if (engine.getSellBook() != null) {
                engine.getSellBook().levels().forEach(entry -> {
                    if (entry != null && entry.getKey() != null && entry.getValue() != null) {
                        long totalQty = 0;
                        try {
                            for (com.matching.engine.CompactOrderBookEntry order : entry.getValue()) {
                                if (order != null) {
                                    totalQty += order.remainingQty;
                                }
                            }
                        } catch (Exception e) {
                            // 并发修改异常，忽略该价格档位
                            return;
                        }
                        if (totalQty > 0) {
                            orderBook.addSellOrder(entry.getKey(), totalQty);
                        }
                    }
                });
            }
        } catch (Exception e) {
            log.warn("从 MatchEngine 构建 OrderBook 失败, 返回空 OrderBook", e);
        }

        return orderBook;
    }

    /**
     * 构建增量深度更新
     */
    public DepthUpdate buildIncrementalDepthUpdate(String symbol, OrderBook orderBook,
                                                  Map<String, Set<java.math.BigDecimal>> changedPriceLevels) {
        FastList<PriceLevel> bids = new FastList<>();
        FastList<PriceLevel> asks = new FastList<>();

        try {
            // 处理变化的买单价格档位
            Set<java.math.BigDecimal> buyChanges = changedPriceLevels.get("buy");
            if (buyChanges != null) {
                for (java.math.BigDecimal price : buyChanges) {
                    long priceKey = com.matching.engine.CompactOrderBookEntry.toLong(price);
                    long quantity = orderBook.getBuyOrders().get(priceKey);
                    bids.add(new PriceLevel(priceKey, quantity));
                }
            }

            // 处理变化的卖单价格档位
            Set<java.math.BigDecimal> sellChanges = changedPriceLevels.get("sell");
            if (sellChanges != null) {
                for (java.math.BigDecimal price : sellChanges) {
                    long priceKey = com.matching.engine.CompactOrderBookEntry.toLong(price);
                    long quantity = orderBook.getSellOrders().get(priceKey);
                    asks.add(new PriceLevel(priceKey, quantity));
                }
            }
        } catch (Exception e) {
            log.warn("构建增量深度更新失败, 返回空更新", e);
        }

        return new DepthUpdate(symbol, bids, asks, System.currentTimeMillis(), false);
    }

    /**
     * 构建完整深度更新（快照）
     */
    public DepthUpdate buildSnapshotDepthUpdate(String symbol, OrderBook orderBook) {
        FastList<PriceLevel> bids = priceLevelPool.get();
        FastList<PriceLevel> asks = priceLevelPool.get();

        try {
            bids.clear();
            asks.clear();

            // 构建买单深度
            orderBook.getBuyOrders().forEachKeyValue((long price, long quantity) -> {
                if (quantity > 0) {
                    bids.add(new PriceLevel(price, quantity));
                }
            });

            // 构建卖单深度
            orderBook.getSellOrders().forEachKeyValue((long price, long quantity) -> {
                if (quantity > 0) {
                    asks.add(new PriceLevel(price, quantity));
                }
            });

            // 按价格排序
            bids.sortThis((PriceLevel a, PriceLevel b) -> Long.compare(b.getPrice(), a.getPrice())); // 买单降序
            asks.sortThis((PriceLevel a, PriceLevel b) -> Long.compare(a.getPrice(), b.getPrice())); // 卖单升序

            return new DepthUpdate(
                    symbol,
                    new FastList<>(bids),
                    new FastList<>(asks),
                    System.currentTimeMillis(),
                    true
            );
        } finally {
            bids.clear();
            asks.clear();
        }
    }
}