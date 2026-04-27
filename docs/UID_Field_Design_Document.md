# uid 字段添加设计文档

## 需求背景

原来的设计中：
- 撮合结果按 `accountId` hash 路由到下游 trade-engine
- 一个用户只能有一个账户

新的设计需求：
- 支持一个用户有多个账户（accountId 是账户级别，uid 是用户级别）
- 撮合结果应该按 `uid` hash 路由（而不是 accountId）
- 需要维护 uid 与 accountId 的对应关系

## 核心概念

### 数据模型关系

```
User (uid)
  ├─ Account 1 (accountId)
  ├─ Account 2 (accountId)
  └─ Account N (accountId)

订单：
- 属于某个账户 (accountId)
- 属于某个用户 (uid)
- 一个用户在一个交易对上的所有订单，不管来自哪个账户，都路由到同一个 trade-engine 分片
```

### 为什么按 uid 路由？

1. **用户级别的业务逻辑统一**：同一个用户的所有交易需要统一处理
2. **风险控制**：同一用户在多个账户的风险需要统一计算
3. **持仓管理**：一个用户可能需要跨账户看总持仓

## 代码改动详情

### 1. Proto 文件修改

**文件**：`src/main/proto/matching.proto` 和 `src/proto/matching.proto`

```protobuf
message PbCancelOrderParam {
  string symbol_id = 1;
  repeated string order_ids = 2;
  int64 account_id = 3;              // 账户ID（订单所属账户）
  bool cancel_all = 4;
  int64 uid = 5;                     // 用户ID（撮合结果路由用）
}
```

改动说明：
- 添加 `int64 uid = 5` 字段
- accountId 保留，用于订单查询（orders 都归属于某个账户）
- uid 新增，用于撮合结果路由（下游按 uid hash）

### 2. DTO 修改

**文件**：`com.matching.dto.CancelOrderParam`

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelOrderParam {
    private String symbolId;           // 交易对
    private List<String> orderIds;     // 要取消的订单ID列表
    private Long accountId;            // 账户ID（订单所属账户）
    private boolean cancelAll;         // 是否取消该账户下的所有订单
    private Long uid;                  // 用户ID（撮合结果按此 hash 路由）
}
```

改动说明：
- 使用 Lombok 的 `@Data` 注解，自动生成所有 getter/setter
- 添加 uid 字段及注释

### 3. Proto 转换层修改

**文件**：`com.matching.util.TradeCommandProtoConverter`

```java
static CancelOrderParam fromProto(PbCancelOrderParam pb) {
    return CancelOrderParam.builder()
            .symbolId(toStr(pb.getSymbolId()))
            .orderIds(pb.getOrderIdsList().isEmpty() ? null : new ArrayList<>(pb.getOrderIdsList()))
            .accountId(pb.getAccountId() != 0 ? pb.getAccountId() : null)
            .cancelAll(pb.getCancelAll())
            .uid(pb.getUid() != 0 ? pb.getUid() : null)  // 新增：从 proto 转换 uid
            .build();
}
```

改动说明：
- 添加 `.uid(pb.getUid() != 0 ? pb.getUid() : null)`
- 遵循原来对 accountId 的处理方式（0 转为 null）

### 4. 命令处理层修改

**文件**：`com.matching.command.CancelOrderCommand`

关键改动：
- 修改 `executeBatchCancel` 方法：获取 uid 并传递给 buildCancelledOrder
- 修改 `executeCancelAll` 方法：获取 uid 并传递给 buildCancelledOrder
- 修改 `buildCancelledOrder` 方法签名：添加 uid 参数，设置到 MatchOrderResult

```java
private MatchOrderResult buildCancelledOrder(
        long orderId, long accountId, long uid, String symbolId, 
        long quantity, long remainingQty) {
    MatchOrderResult order = new MatchOrderResult();
    order.setOrderId(orderId);
    order.setAccountId(accountId);
    order.setUid(uid);                    // 新增：设置 uid
    order.setSymbolId(symbolId);
    // ... 其他字段 ...
    return order;
}
```

改动说明：
- uid 来自于 `compact.uid`（订单本身携带的用户ID）
- 确保撮合结果中的 uid 与原订单一致

## 数据流转说明

### 取消订单的完整流程

```
1. 客户端下单
   ├─ PlaceOrderCommand
   │  └─ 创建订单时，从 placeParam 获取 uid
   │     └─ 订单在 CompactOrderBookEntry 中存储 uid

2. 取消订单
   ├─ ReceiverMq 收到 Kafka 消息
   │  └─ ProtoConverter.fromProto(PbCancelOrderParam)
   │     └─ 转换并读取 uid（来自客户端请求）
   │
   ├─ MatchEventHandler.onEvent() 处理
   │  └─ CancelOrderCommand.execute(cancelParam)
   │     ├─ 获取 cancelParam.getUid()
   │     └─ 取消订单，构建 MatchOrderResult
   │        └─ buildCancelledOrder(..., uid, ...)
   │           └─ MatchOrderResult.setUid(uid)
   │
   ├─ Disruptor 发布 MatchResult
   └─ 撮合结果按 uid 路由
      └─ ReceiverMq.splitByUid()
         ├─ 检查 dealtOrders 中的 uid
         ├─ 按 uid 分组
         └─ Kafka key = uid hash
            └─ 下游 trade-engine 按 uid 消费
```

### 关键数据字段流转

```
PlaceOrderParam
└─ uid（用户下单时提供）
   └─ CompactOrderBookEntry.uid
      └─ CancelOrderCommand 读取
         └─ MatchOrderResult.uid
            └─ Kafka message key = uid (via splitByUid)
```

## 错误处理和边界情况

### 1. uid 为 null 或 0

**处理方式**：
- Proto 转换时：`pb.getUid() != 0 ? pb.getUid() : null`
- 这与 accountId 处理方式一致

**隐患**：
- 如果 uid 为 null，ReceiverMq.splitByUid() 会跳过该订单并告警
- 需要在 OrderValidator 中验证 uid 不为 null

### 2. uid 与 accountId 不匹配

**需要验证的地方**：
- OrderValidator 中，需要检查该 uid 是否确实拥有该 accountId
- CancelOrderCommand 中，应该验证要取消的订单的 accountId 与请求的 accountId 一致

### 3. 跨账户取消

**当前设计**：
- `cancelAll` 取消某个账户下的所有订单
- 只取消该账户的订单，不跨账户取消

**未来可能的扩展**：
- 支持按 uid 取消所有账户下的所有订单
- 需要新增参数如 `cancelAllByUid`

## 验证方案

### 1. 单元测试需要更新

- `CancelOrderCommandTest`：需要在 buildCancelledOrder 调用中添加 uid 参数
- `TradeCommandProtoConverterTest`：验证 uid 的正确转换

### 2. 集成测试需要验证

- Kafka 消息中的 uid 是否正确
- ReceiverMq.splitByUid() 是否按 uid 正确分组
- 能否正确处理 uid 为 0 或 null 的情况

### 3. 数据验证检查表

| 检查项 | 检查内容 | 通过条件 |
|--------|--------|--------|
| Proto 编译 | PbCancelOrderParam 是否有 getUid() 方法 | ✓ |
| DTO 转换 | CancelOrderParam 是否有 uid 字段 | ✓ |
| Proto 转换 | fromProto() 是否正确读取 uid | ✓ |
| 命令执行 | buildCancelledOrder 是否设置 uid | ✓ |
| 结果路由 | splitByUid() 是否使用该 uid | ✓（已有） |

## 配置 vs 业务逻辑

### 与 accountId 的区别

| 项目 | accountId | uid |
|------|-----------|-----|
| 级别 | 账户级别 | 用户级别 |
| 查询 | 订单按 accountId 分组存储在 OrderBook | 无存储，仅用于路由 |
| 路由 | 原来用于 Kafka key（已改为 uid） | 现在用于 Kafka key |
| 验证 | OrderValidator 必须验证 | OrderValidator 必须验证，且需验证与 accountId 的关系 |
| 取消 | cancelAll 按 accountId 取消所有订单 | 无直接取消接口 |

## 兼容性分析

### 向后兼容性

**潜在问题**：
- 旧的 CancelOrderParam 对象（没有 uid）如何处理？
- 旧的客户端可能不发送 uid？

**处理方案**：
- 使用 Builder 构造 CancelOrderParam 时，uid 是可选的（default null）
- 如果 uid 为 null，ReceiverMq.splitByUid() 会告警并跳过
- OrderValidator 中应该强制验证 uid 不为 null

## 下一步工作

1. **OrderValidator 修改**：添加 uid 验证逻辑
2. **测试用例更新**：所有涉及 CancelOrderParam 的测试
3. **客户端适配**：确保所有下单请求都携带 uid
4. **监控告警**：监控 uid 为 null 的情况

## 相关类关系图

```
ReceiverMq
  ├─ ProtoConverter.fromProto(PbCancelOrderParam)
  │  └─ TradeCommandProtoConverter.fromProto()
  │     └─ CancelOrderParam (带 uid)
  │
  ├─ MatchEventHandler.onEvent()
  │  └─ CancelOrderCommand.execute(cancelParam)
  │     ├─ 读取 uid
  │     └─ buildCancelledOrder(..., uid, ...)
  │        └─ MatchOrderResult.setUid(uid)
  │
  └─ splitByUid(MatchResult)
     └─ 按 dealtOrders.uid 分组
```

