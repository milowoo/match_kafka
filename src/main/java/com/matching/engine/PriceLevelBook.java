package com.matching.engine;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 高性能价格级别订单簿，使用跳表结构。价格查找-O(log n)，跳表常数因子小且稳定。有序遍历-O(n)
 * 天然有序，无需额外排序。插入/删除-O(log n)，无数据移动，延迟稳定。并发友好，支持无锁读取，适合高频交
 */
public class PriceLevelBook {
    // 跳表：价格 -> OrderList，天然有序且并发友好
    private final ConcurrentSkipListMap<Long, OrderList> priceToOrders;
    // 买卖降序、卖盘升序
    private final boolean descending;

    public PriceLevelBook(boolean descending) {
        this.descending = descending;
        // 根据买卖方向创建相应的比较器
        if (descending) {
            // 买盘：价格降序（高价优先）
            this.priceToOrders = new ConcurrentSkipListMap<>(Collections.reverseOrder());
        } else {
            // 卖盘：价格升序（低价优先）
            this.priceToOrders = new ConcurrentSkipListMap<>();
        }
    }

    public int size() {
        return priceToOrders.size();
    }

    public boolean isEmpty() {
        return priceToOrders.isEmpty();
    }

    // 获取最佳价格 - O(log n) 但常数小
    public long bestPrice() {
        try {
            if (priceToOrders.isEmpty()) {
                return 0;
            }
            Long firstKey = priceToOrders.firstKey();
            return firstKey != null ? firstKey : 0;
        } catch (NoSuchElementException e) {
            // 在高并发情况下，可能在检查后被清空
            return 0;
        }
    }

    // 根据索引获取价格 - O(n) 需要遍历到指定位置
    public long priceAt(int index) {
        if (index < 0 || index >= priceToOrders.size()) {
            return 0;
        }
        int i = 0;
        for (Long price : priceToOrders.keySet()) {
            if (i == index) {
                return price;
            }
            i++;
        }
        return 0;
    }

    // 根据索引获取订单列表 - O(n) 需要遍历到指定位置
    public OrderList ordersAt(int index) {
        if (index < 0 || index >= priceToOrders.size()) {
            return null;
        }
        int i = 0;
        for (Map.Entry<Long, OrderList> entry : priceToOrders.entrySet()) {
            if (i == index) {
                return entry.getValue();
            }
            i++;
        }
        return null;
    }

    // 根据价格获取订单列表 - O(log n) 跳表查找 虽然不是O(1)，但跳表常数因子小且延迟稳定
    public OrderList ordersAtPrice(long price) {
        return priceToOrders.get(price);
    }

    // 检查价格是否存在并返回实际索引 - O(n)
    public int indexOf(long price) {
        if (!priceToOrders.containsKey(price)) {
            return -1;
        }
        int index = 0;
        for (Long p : priceToOrders.keySet()) {
            if (p.equals(price)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    // 添加订单到订单簿 - O(log n) 跳表插入
    public void addOrder(CompactOrderBookEntry entry) {
        OrderList list = priceToOrders.get(entry.price);
        if (list == null) {
            // 新价格级别
            list = new OrderList();
            priceToOrders.put(entry.price, list);
        }
        list.add(entry);
    }

    // 根据价格和订单ID删除订单 - O(log n) + O(k)
    public boolean removeOrder(long price, long orderId) {
        OrderList list = priceToOrders.get(price);
        if (list == null) return false;

        boolean removed = list.removeById(orderId);
        if (removed && list.isEmpty()) {
            priceToOrders.remove(price);
        }
        return removed;
    }

    // 根据订单引用删除订单 - O(log n) + O(1)
    public void removeEntry(CompactOrderBookEntry entry) {
        OrderList list = priceToOrders.get(entry.price);
        if (list == null) return;

        list.remove(entry);
        if (list.isEmpty()) {
            priceToOrders.remove(entry.price);
        }
    }

    // 删除最佳价格级别 - O(log n) 跳表删除
    public void removeBestLevel() {
        if (priceToOrders.isEmpty()) return;
        Long bestPrice = priceToOrders.firstKey();
        if (bestPrice != null) {
            priceToOrders.remove(bestPrice);
        }
    }

    // 获取第一个价格级别条目 - O(log n)
    public Map.Entry<Long, OrderList> firstEntry() {
        if (priceToOrders.isEmpty()) {
            return null;
        }
        return priceToOrders.firstEntry();
    }

    // 获取所有价格级别用于遍历 - O(n) 天然有序
    public Set<Map.Entry<Long, OrderList>> levels() {
        return priceToOrders.entrySet();
    }

    // 收集所有订单 - O(n*k) 天然有序遍历
    public List<CompactOrderBookEntry> allOrders() {
        List<CompactOrderBookEntry> all = new ArrayList<>();
        for (OrderList list : priceToOrders.values()) {
            if (list != null) {
                all.addAll(list.toList());
            }
        }
        return all;
    }

    // 跳表结构无需手动管理数组扩容和排序
    // 所有复杂的数组操作都被跳表内部优化的算法替代

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("PriceLevelBook(")
          .append("descending=").append(descending)
          .append(", priceCount=").append(priceToOrders.size())
          .append(", prices=[");
        int count = 0;
        for (Long price : priceToOrders.keySet()) {
            if (count > 0) sb.append(", ");
            sb.append(price);
            if (++count >= 5) break;
        }
        if (priceToOrders.size() > 5) sb.append("...");
        sb.append("])");
        return sb.toString();
    }
}