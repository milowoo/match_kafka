package com.matching.engine;

import com.matching.dto.OrderBookEntry;
import java.util.HashMap;
import java.util.List;

public class MatchEngine {

    private static final int DEFAULT_POOL_SIZE = 10000;
    private static final int DEFAULT_MAX_ORDERS = 50000;

    private final String symbolId;
    private final PriceLevelBook buyBook;
    private final PriceLevelBook sellBook;
    private final EntryPool entryPool;
    private final HashMap<Long, CompactOrderBookEntry> orderIndex;
    private final HashMap<Long, Integer> accountOrderCount;
    private final int maxOrders;

    /**
     * 构造函数 - 创建指定交易对的订单簿引擎（使用默认最大订单数50000）
     *
     * @param symbolId 交易对ID（如"BTC/USDT"）
     */
    public MatchEngine(String symbolId) {
        this(symbolId, DEFAULT_MAX_ORDERS);
    }

    /**
     * 构造函数 - 创建指定交易对的订单簿引擎（自定义最大订单数）
     *
     * @param symbolId 交易对ID（如"BTC/USDT"）
     * @param maxOrders 单个订单簿的最大订单数限制
     *
     * 初始化内容：
     * - buyBook: 买方订单簿（降序排列：价格越高越靠前）
     * - sellBook: 卖方订单簿（升序排列：价格越低越靠前）
     * - entryPool: 对象池，用于回收CompactOrderBookEntry对象，减少GC压力
     * - orderIndex: 订单ID到订单对象的映射，支持O(1)快速查询
     * - accountOrderCount: 账户ID到该账户订单数的映射，用于检查账户订单限制
     *
     * 容量计算说明：
     * - orderIndex容量为 (maxOrders * 4 + 2) / 3，相当于 maxOrders / 0.75
     * - 这样可以确保HashMap的负载因子为0.75，避免频繁rehash
     * - accountCapacity 至少为1024，最多为 maxOrders / 100
     */
    public MatchEngine(String symbolId, int maxOrders) {
        this.symbolId = symbolId;
        this.maxOrders = maxOrders;
        this.buyBook = new PriceLevelBook(true);
        this.sellBook = new PriceLevelBook(false);
        this.entryPool = new EntryPool(EntryPool.DEFAULT_POOL_SIZE);

        // 使用整数运算避免float精度损失：(maxOrders * 4 + 2) / 3 等价于 maxOrders / 0.75
        // 这样可以确保HashMap不会因为容量不足而触发rehash
        int orderIndexCapacity = (maxOrders * 4 + 2) / 3;
        int accountCapacity = Math.max(1024, (maxOrders + 99) / 100);
        this.orderIndex = new HashMap<>(orderIndexCapacity);
        this.accountOrderCount = new HashMap<>(accountCapacity);
    }

    /**
     * 获取交易对ID
     *
     * @return 该引擎管理的交易对ID
     */
    public String getSymbolId() {
        return symbolId;
    }

    /**
     * 获取买方订单簿
     *
     * @return 按价格降序排列的买方订单簿（价格越高越靠前）
     */
    public PriceLevelBook getBuyBook() {
        return buyBook;
    }

    /**
     * 获取卖方订单簿
     *
     * @return 按价格升序排列的卖方订单簿（价格越低越靠前）
     */
    public PriceLevelBook getSellBook() {
        return sellBook;
    }

    /**
     * 从对象池中获取一个CompactOrderBookEntry对象
     *
     * @return 可复用的订单对象，若池为空则新建一个
     *
     * 用途：
     * - 创建新订单时调用此方法获取对象，避免重复分配内存
     * - 与 releaseEntry() 配对使用，形成对象池的生命周期
     */
    public CompactOrderBookEntry acquireEntry() {
        return entryPool.acquire();
    }

    /**
     * 将订单对象归还至对象池（不修改orderIndex或accountOrderCount）
     *
     * @param entry 要归还的订单对象
     *
     * 用途：
     * - 用于归还从未被加入订单簿的订单（如被拒绝或取消的吃单订单）
     * - 注意：此方法只释放对象到池，不修改订单簿或索引
     * - 如果订单已加入订单簿，应该使用 removeFilledOrder() 或 cancelOrder() 代替
     *
     * 典型场景：
     * - 下单被拒绝（校验不通过、穿仓保护等）
     * - FOK订单无法全部成交，取消剩余部分
     * - 市价单流动性不足，取消未成交部分
     */
    public void releaseEntry(CompactOrderBookEntry entry) {
        entryPool.release(entry);
    }

    /**
     * 将订单加入订单簿
     *
     * @param entry 要加入的订单对象（已包含所有必要信息）
     * @return true 表示成功加入，false 表示订单簿已满，无法加入
     *
     * 加入流程：
     * 1. 检查订单总数是否达到上限 (maxOrders)
     * 2. 根据订单方向(side)加入相应的买/卖订单簿
     * 3. 在订单索引中记录订单ID与对象的映射
     * 4. 更新账户订单计数
     *
     * 前置条件：
     * - 订单对象必须完全初始化，包含orderId、accountId、price、quantity、side等
     * - 订单ID必须唯一（不应重复）
     *
     * 后置效果：
     * - 订单成为挂单方，等待与吃单匹配
     * - orderIndex 和 accountOrderCount 被更新
     *
     * 容量管理：
     * - 若订单簿满返回false，调用方应该处理此情况（如返回ORDER_BOOK_FULL错误）
     * - 建议配合 isFull() 或 orderCount() 进行监控
     */
    public boolean addOrder(CompactOrderBookEntry entry) {
        if (orderIndex.size() >= maxOrders) {
            return false;
        }

        PriceLevelBook book = entry.side == CompactOrderBookEntry.BUY ? buyBook : sellBook;
        book.addOrder(entry);
        orderIndex.put(entry.orderId, entry);
        accountOrderCount.merge(entry.accountId, 1, Integer::sum);
        return true;
    }

    /**
     * 查询订单（通过订单ID）
     *
     * @param orderId 要查询的订单ID
     * @return 对应的订单对象，若不存在则返回null
     *
     * 复杂度：O(1)，通过HashMap快速查询
     *
     * 用途：
     * - 取消订单时快速查询订单对象
     * - 查询订单状态或详细信息
     *
     * 返回null情况：
     * - 订单不存在或已被移除（成交或取消）
     * - 订单ID输入错误
     */
    public CompactOrderBookEntry getOrder(long orderId) {
        return orderIndex.get(orderId);
    }

    /**
     * 移除已成交的订单 - 从订单簿、索引、账户计数中全部移除并归还对象池
     *
     * @param entry 要移除的订单对象（已完全成交）
     *
     * 移除流程：
     * 1. 从订单索引中移除
     * 2. 减少账户订单计数
     * 3. 将对象归还至对象池
     *
     * 前置条件：
     * - 订单必须已从订单簿的OrderList中移除（通过iterator.remove()）
     * - 订单已完全成交
     *
     * 注意：
     * - 此方法假设订单已从book中移除，不再调用book.removeEntry()
     * - 配合撮合循环中的 it.remove() 使用
     */
    public void removeFilledOrder(CompactOrderBookEntry entry) {
        orderIndex.remove(entry.orderId);
        decrementAccountCount(entry.accountId);
        entryPool.release(entry);
    }

    /**
     * 取消订单 - 从订单簿、索引、账户计数中全部移除并归还对象池
     *
     * @param orderId 要取消的订单ID
     * @return 被取消的订单对象，若订单不存在则返回null
     *
     * 取消流程：
     * 1. 从订单索引中查询并移除订单
     * 2. 从相应的买/卖订单簿中移除该订单
     * 3. 减少账户订单计数
     * 4. 将对象归还至对象池
     *
     * 返回null情况：
     * - 订单不存在（已成交、已取消或ID错误）
     *
     * 用途：
     * - 用户主动取消订单
     * - 订单被系统取消（如风险控制）
     *
     * 原子性：
     * - 整个取消过程中，订单不会出现不一致的状态
     * - 如果查询失败则直接返回null，不做任何修改
     */
    public CompactOrderBookEntry cancelOrder(long orderId) {
        CompactOrderBookEntry entry = orderIndex.remove(orderId);
        if (entry == null) return null;

        PriceLevelBook book = entry.side == CompactOrderBookEntry.BUY ? buyBook : sellBook;
        book.removeEntry(entry);
        decrementAccountCount(entry.accountId);
        entryPool.release(entry);
        return entry;
    }

    /**
     * 批量加载订单 - 从持久化存储恢复订单簿数据
     *
     * @param entries 订单列表（来自数据库或EventLog回放）
     *
     * 加载流程：
     * 1. 遍历所有订单
     * 2. 跳过已存在的订单（防止重复）
     * 3. 将订单转换为CompactOrderBookEntry格式
     * 4. 加入相应的买/卖订单簿
     * 5. 更新索引和账户计数
     *
     * 防重机制：
     * - 检查orderIndex中是否已存在该订单ID
     * - 防止冰山单刷新、EventLog回放时的重复挂单
     * - 防止MAL（主从同步日志）回放时的重复
     *
     * 用途：
     * - HA切换时恢复订单簿
     * - 启动时从数据库加载历史订单
     * - 从EventLog回放恢复状态
     *
     * 幂等性：
     * - 多次调用相同的entries列表不会产生重复订单
     * - 可以安全地用于故障恢复
     */
    public void loadOrders(List<OrderBookEntry> entries) {
        for (OrderBookEntry dto : entries) {
            long orderId = Long.parseLong(dto.getClientOrderId());
            // 跳过已存在的订单（防止冰山单刷新等场景下EventLog回放重复）
            if (orderIndex.containsKey(orderId)) {
                continue;
            }

            CompactOrderBookEntry entry = CompactOrderBookEntry.fromEntry(dto);
            PriceLevelBook book = entry.side == CompactOrderBookEntry.BUY ? buyBook : sellBook;
            book.addOrder(entry);
            orderIndex.put(entry.orderId, entry);
            accountOrderCount.merge(entry.accountId, 1, Integer::sum);
        }
    }

    /**
     * 从DTO对象添加订单 - 将订单数据从DTO格式转换并加入订单簿
     *
     * @param dto 订单DTO对象（来自API或MAL回放）
     * @return true 表示成功加入，false 表示订单簿已满或订单已存在
     *
     * 添加流程：
     * 1. 从DTO中提取订单ID
     * 2. 检查是否已存在（防重）
     * 3. 检查订单簿是否已满
     * 4. 从对象池获取新订单对象
     * 5. 将DTO字段转换到CompactOrderBookEntry：
     *    - 基础字段：orderId、accountId、price、quantity、remainingQty等
     *    - 订单属性：VIP标记、side（买/卖）
     *    - 冰山单字段（若需要）：icebergTotalQuantity、icebergDisplayQuantity等
     * 6. 加入订单簿、索引、账户计数
     *
     * 防重机制：
     * - 检查orderIndex中是否已存在该订单ID
     * - 防止MAL回放时的重复挂单
     *
     * 冰山单支持：
     * - 识别冰山单标记（dto.isIceberg()）
     * - 初始化冰山单相关字段
     *
     * 返回值：
     * - true: 订单成功加入
     * - false: 订单簿已满或订单已存在
     *
     * 用途：
     * - MAL（主从同步日志）回放
     * - 从API或其他系统导入订单
     */
    public boolean addOrderFromDTO(OrderBookEntry dto) {
        long orderId = Long.parseLong(dto.getClientOrderId());
        // 跳过已存在的订单（防止MAL回放重复挂单）
        if (orderIndex.containsKey(orderId)) {
            return true;
        }

        if (orderIndex.size() >= maxOrders) {
            return false;
        }

        CompactOrderBookEntry entry = entryPool.acquire();
        entry.orderId = orderId;
        entry.accountId = dto.getAccountId();
        entry.price = CompactOrderBookEntry.toLong(dto.getPrice());
        entry.quantity = CompactOrderBookEntry.toLong(dto.getQuantity());
        entry.remainingQty = CompactOrderBookEntry.toLong(dto.getRemainingQuantity());
        entry.requestTime = dto.getRequestTime();
        entry.side = "buy".equalsIgnoreCase(dto.getSide()) ? CompactOrderBookEntry.BUY : CompactOrderBookEntry.SELL;
        entry.vip = dto.isVip();

        // 冰山单字段
        if (dto.isIceberg()) {
            entry.isIceberg = true;
            entry.icebergTotalQuantity = CompactOrderBookEntry.toLong(dto.getIcebergTotalQuantity());
            entry.icebergHiddenQuantity = CompactOrderBookEntry.toLong(dto.getIcebergHiddenQuantity());
            entry.icebergDisplayQuantity = CompactOrderBookEntry.toLong(dto.getIcebergDisplayQuantity());
            entry.icebergRefreshQuantity = CompactOrderBookEntry.toLong(dto.getIcebergRefreshQuantity());
        }

        PriceLevelBook book = entry.side == CompactOrderBookEntry.BUY ? buyBook : sellBook;
        book.addOrder(entry);
        orderIndex.put(entry.orderId, entry);
        accountOrderCount.merge(entry.accountId, 1, Integer::sum);
        return true;
    }

    /**
     * 获取指定账户的订单数
     *
     * @param accountId 账户ID
     * @return 该账户在本交易对中的订单数，若无订单则返回0
     *
     * 用途：
     * - 检查账户订单数是否超过限制（风险控制）
     * - 监控账户活动
     *
     * 复杂度：O(1)
     */
    public int getAccountOrderCount(long accountId) {
        return accountOrderCount.getOrDefault(accountId, 0);
    }

    /**
     * 减少指定账户的订单计数 - 内部方法
     *
     * @param accountId 账户ID
     *
     * 逻辑：
     * - 如果计数 <= 1，则从map中移除该账户
     * - 否则，计数减1
     * - 这样可以避免map中堆积空键（计数为0的账户）
     */
    private void decrementAccountCount(long accountId) {
        accountOrderCount.computeIfPresent(accountId, (k, v) -> v <= 1 ? null : v - 1);
    }

    /**
     * 增加指定账户的订单计数 - 内部方法
     *
     * @param accountId 账户ID
     *
     * 逻辑：
     * - 如果账户不存在，初始化计数为1
     * - 否则，计数加1
     */
    public void incrementAccountCount(long accountId) {
        accountOrderCount.merge(accountId, 1, Integer::sum);
    }

    /**
     * 获取当前订单总数
     *
     * @return 订单簿中的订单数量
     *
     * 用途：
     * - 监控订单簿负载
     * - 检查是否接近容量上限
     *
     * 复杂度：O(1)
     */
    public int orderCount() {
        return orderIndex.size();
    }

    /**
     * 获取订单簿的最大容量
     *
     * @return 订单簿允许的最大订单数
     */
    public int maxOrders() {
        return maxOrders;
    }

    /**
     * 获取对象池的可用对象数
     *
     * @return 对象池中可用的CompactOrderBookEntry对象数
     *
     * 用途：
     * - 监控对象池的使用情况
     * - 调试GC相关问题
     */
    public int poolAvailable() {
        return entryPool.available();
    }

    /**
     * 检查订单簿是否已满
     *
     * @return true 表示订单数已达到上限，false 表示还有容量
     *
     * 用途：
     * - 快速判断是否能接受新订单
     * - 在addOrder()前进行预检查
     */
    public boolean isFull() {
        return orderIndex.size() >= maxOrders;
    }

    /**
     * 刷新冰山单显示部分 - 当冰山单的显示部分完全成交后，自动展露隐藏部分
     *
     * @param order 要刷新的冰山单对象
     *
     * 前置条件（重要）：
     * - 订单必须已从 OrderList 链表中移除（通过 it.remove()）
     * - 订单必须已从 orderIndex 中移除
     * - 订单必须是冰山单（isIceberg=true）
     * - 订单必须有隐藏部分（icebergHiddenQuantity > 0）
     *
     * 刷新流程：
     * 1. 检查订单是否为冰山单且有隐藏部分
     * 2. 计算新的显示数量：min(icebergRefreshQuantity, icebergHiddenQuantity)
     * 3. 更新订单字段：
     *    - remainingQty = 新显示数量
     *    - icebergDisplayQuantity = 新显示数量
     *    - icebergHiddenQuantity -= 新显示数量
     * 4. 将订单重新加入订单簿
     * 5. 将订单重新添加至订单索引
     *
     * 场景示例：
     * - 冰山单初始：显示1000，隐藏4000
     * - 显示部分1000全部成交
     * - 刷新：新显示1000，新隐藏3000
     *
     * 注意：
     * - 不更新账户订单计数（因为订单仍在簿中，只是从隐藏变为显示）
     * - 如果隐藏部分为0，则此方法为no-op
     */
    public void refreshIcebergOrder(CompactOrderBookEntry order) {
        if (!order.isIceberg || order.icebergHiddenQuantity <= 0) {
            return;
        }

        long newDisplayQty = Math.min(order.icebergRefreshQuantity, order.icebergHiddenQuantity);
        order.remainingQty = newDisplayQty;
        order.icebergDisplayQuantity = newDisplayQty;
        order.icebergHiddenQuantity -= newDisplayQty;

        PriceLevelBook book = order.side == CompactOrderBookEntry.BUY ? buyBook : sellBook;
        book.addOrder(order);
        orderIndex.put(order.orderId, order);
    }

    /**
     * 从订单索引中移除订单引用（不从订单簿中移除，配合Iterator.remove()使用）
     *
     * @param orderId 要移除的订单ID
     *
     * 用途：
     * - 在撮合循环中，使用Iterator遍历OrderList时
     * - 订单被移除出OrderList，但还需要从orderIndex中清除
     * - 用于避免订单同时存在于book和index中
     *
     * 典型场景：
     * ```java
     * Iterator<CompactOrderBookEntry> it = orderList.iterator();
     * while (it.hasNext()) {
     *     CompactOrderBookEntry maker = it.next();
     *     if (maker.isFilled()) {
     *         it.remove();  // 从OrderList中移除
     *         matchEngine.removeFromIndex(maker.orderId);  // 从索引中移除
     *     }
     * }
     * ```
     *
     * 注意：
     * - 此方法只移除索引引用，不释放对象到对象池
     * - 不更新账户订单计数
     * - 配合 removeFilledOrder() 或其他清理方法使用
     */
    public void removeFromIndex(long orderId) {
        orderIndex.remove(orderId);
    }
}