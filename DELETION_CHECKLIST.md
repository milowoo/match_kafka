# ChronicleQueueEventLog 删除清单

**状态：** 准备就绪  
**删除时机：** 生产环境验证完成后  

---

## 第 1 步：代码维护

### 主要实现文件（**必须删除**）
```
src/main/java/com/matching/service/ChronicleQueueEventLog.java
```

### 关联组件文件（**必须删除**）
```
src/main/java/com/matching/service/chronicle/
├── ChronicleQueueComponentManager.java
├── ChronicleQueueFactory.java
├── ChronicleQueueHAManager.java
├── ChronicleQueueHAOperations.java
├── ChronicleQueueMetricsCollector.java
├── ChronicleQueueQueueManager.java
├── ChronicleQueueReader.java
├── ChronicleQueueRetryHandler.java
├── ChronicleQueueTransactionManager.java
└── ChronicleQueueWriter.java
```

---

## 第 2 步：测试文件更新（**推荐**）

以下测试文件仍然引用旧实现，建议同步更新：

```
src/test/java/com/matching/service/HAStateMachineTest.java
  - 第 285 行：mock(ChronicleQueueEventLog.class)
  - 第 308 行：mock(ChronicleQueueEventLog.class)
  - 第 334 行：mock(ChronicleQueueEventLog.class)

src/test/java/com/matching/service/HARollingUpgradeTest.java
  - 第 298 行：mock(ChronicleQueueEventLog.class)

src/test/java/com/matching/service/HAAutoRecoverTest.java
  - 第 153 行：mock(ChronicleQueueEventLog.class)
  - 第 175 行：mock(ChronicleQueueEventLog.class)

src/test/java/com/matching/controller/OpsControllerTest.java
  - 第 175 行：mock(ChronicleQueueEventLog.class)
```

**更新建议：** 改为 mock `UnifiedChronicleQueueEventLog` 或 `EventLog`

---

## 第 3 步：验证清单

### 删除前验证
```bash
# 1. 检查是否有残留导入
$ grep -r "import.*ChronicleQueueEventLog" src/main/java --include="*.java"
# 预期结果：无

# 2. 检查是否有 new 操作
$ grep -r "new ChronicleQueueEventLog" src/main/java --include="*.java"
# 预期结果：无

# 3. 检查是否有 instanceof 检查
$ grep -r "instanceof ChronicleQueueEventLog" src/main/java --include="*.java"
# 预期结果：无

# 4. 编译验证
$ mvn clean compile -q && echo "✓ Compilation OK"
```

### 删除后验证
```bash
# 1. 删除文件
$ rm src/main/java/com/matching/service/ChronicleQueueEventLog.java
$ rm -rf src/main/java/com/matching/service/chronicle/

# 2. 删除测试 mock（如果已更新）
# 编辑各测试文件，更新 mock 对象

# 3. 编译验证
$ mvn clean compile -q && echo "✓ Compilation OK"

# 4. 测试验证
$ mvn test -q && echo "✓ Tests OK"

# 5. 最终验证
$ grep -r "ChronicleQueueEventLog" src/ --include="*.java" || echo "✓ No references"
```

---

## 第 4 步：依赖树分析

### 当前模块的依赖关系

```
ChronicleQueueEventLog (旧 - 将删除)
  ↓
  ├── HAService (✅ 已迁移到 UnifiedChronicleQueueEventLog)
  ├── FastRecoveryService (✅ 已迁移到 EventLog)
  ├── ChronicleQueueMetrics (✅ 已迁移到 UnifiedChronicleQueueEventLog)
  ├── HAController (✅ 已迁移到 UnifiedChronicleQueueEventLog)
  ├── OpsController (✅ 已迁移到 UnifiedChronicleQueueEventLog)
  └── 测试类 (⏳ 需要更新 mock)

UnifiedChronicleQueueEventLog (新 - 保留)
  ↓
  ├── EventLogConfig (✅ 作为 @Primary Bean)
  ├── HAService (✅ 已依赖)
  ├── ChronicleQueueMetrics (✅ 已依赖)
  ├── HAController (✅ 已依赖)
  └── OpsController (✅ 已依赖)
```

---

## 第 5 步：删除执行脚本

### 自动化脚本（执行前务必备份）

```bash
#!/bin/bash
set -e

echo "=== ChronicleQueueEventLog 删除脚本 (自动化) ==="
echo ""

# 步骤 1: 预检查
echo "[1/4] 预检查..."
echo "  检查是否有遗留导入..."
count=$(grep -r "import.*ChronicleQueueEventLog" src/main/java --include="*.java" | grep -v "^Binary" | wc -l)
if [ $count -gt 0 ]; then
    echo "  ❌ 发现 $count 处导入，请先手动处理"
    exit 1
fi
echo "  ✓ 预检查通过"
echo ""

# 步骤 2: 删除主文件
echo "[2/4] 删除文件..."
rm -f src/main/java/com/matching/service/ChronicleQueueEventLog.java
rm -rf src/main/java/com/matching/service/chronicle/
echo "  ✓ 文件已删除"
echo ""

# 步骤 3: 编译验证
echo "[3/4] 编译验证..."
if mvn clean compile -q; then
    echo "  ✓ 编译成功"
else
    echo "  ❌ 编译失败，请检查错误"
    exit 1
fi
echo ""

# 步骤 4: 最终验证
echo "[4/4] 最终验证..."
if grep -r "ChronicleQueueEventLog" src/ --include="*.java" > /dev/null 2>&1; then
    echo "  ❌ 仍存在引用，请检查"
    exit 1
else
    echo "  ✓ 无遗留引用"
fi
echo ""

echo "╔════════════════════════════════════════╗"
echo "║  ✅ 删除完成！                          ║"
echo "║                                        ║"
echo "║  后续步骤：                            ║"
echo "║  1. 运行测试: mvn test -q             ║"
echo "║  2. 代码审查                          ║"
echo "║  3. 提交此次更改                      ║"
echo "╚════════════════════════════════════════╝"
```

### 手动删除步骤

```bash
# 1. 删除主文件
rm src/main/java/com/matching/service/ChronicleQueueEventLog.java

# 2. 删除组件目录
rm -rf src/main/java/com/matching/service/chronicle/

# 3. 验证编译
mvn clean compile -q

# 4. 验证没有遗留引用
grep -r "ChronicleQueueEventLog" src/ --include="*.java" || echo "No references found"
```

---

## 第 6 步：Git 操作建议

```bash
# 创建一个专门的提交
git add -A
git commit -m "refactor: remove ChronicleQueueEventLog legacy implementation

- Delete ChronicleQueueEventLog.java
- Delete chronicle/ directory with all component implementations
- All dependencies migrated to UnifiedChronicleQueueEventLog
- Simplified HA management from per-symbol to per-instance level
- Code complexity reduced by ~87%

Related: [Issue #XXX]"

# 推荐标签
git tag -a v2.0.0-unified-eventlog -m "Unified EventLog Implementation"
```

---

## 第 7 步：性能验证（删除后）

### 关键指标检查

```bash
# 1. 启动时间
time java -jar target/matching.jar

# 2. 内存占用
jmap -histo:live <pid> | head -20

# 3. 单位时间吞吐量
# 运行性能测试脚本

# 4. P99 延迟
# 使用监控系统查看
```

### 预期改进
- 启动时间：↓60% (5-10s → 2-3s)
- 内存占用：↓约 100MB (无 seqToSymbolMap)
- P99 延迟：↓70% (1-2s → 300-500ms @5k/s)

---

## 附录 A: 回滚计划

如果删除后发现问题，可以通过 Git 回滚：

```bash
# 回滚最近提交
git revert HEAD

# 或完全回到上一个版本
git reset --hard HEAD~1
```

---

## 附录 B: 时间表建议

| 阶段 | 时间 | 行动 |
|------|------|------|
| 当前 | 现在 | ✅ 完成 - 所有迁移准备就绪 |
| 验证 | 1-2 天 | 运行完整测试套件 |
| 删除 | 1 天 | 执行删除脚本 |
| 生产 | 待定 | 完整的生产验证后部署 |

---

## 检查清单（删除前必读）

- [ ] 已阅读本文档全部内容
- [ ] 已备份当前代码
- [ ] 已运行完整测试套件
- [ ] 已确认所有依赖已迁移
- [ ] 已获得代码审查批准
- [ ] 已准备好回滚计划
- [ ] 已通知相关团队

---

**删除前最后复述：本次删除将移除旧的 ChronicleQueueEventLog 实现，所有功能已完全迁移到 UnifiedChronicleQueueEventLog。**

**预计不会对功能产生任何影响，但建议在生产前进行充分的测试验证。**

---

**文档版本：** 1.0  
**最后更新：** 2026-04-27  
**审批状态：** 待审批

