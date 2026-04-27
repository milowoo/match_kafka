# 撮合系统运维脚本使用指南

本目录包含了撮合系统的各种运维脚本，用于系统部署、监控、清理和主备切换等操作。

## 脚本列表

### 1. chronicle_cleanup.sh - Chronicle Queue清理管理
Chronicle Queue文件清理和管理脚本。

**主要功能:**
- 定时清理过期的Chronicle Queue文件
- 按大小限制清理文件
- 预览清理操作
- 监控清理状态
- 创建检查点

**使用示例:**
```bash
# 清理所有交易对
./chronicle_cleanup.sh cleanup-all

# 清理指定交易对
./chronicle_cleanup.sh cleanup BTCUSDT

# 查看清理状态
./chronicle_cleanup.sh status

# 预览清理操作（不实际删除）
./chronicle_cleanup.sh preview ETHUSDT

# 查看存储大小
./chronicle_cleanup.sh size BTCUSDT

# 实时监控清理状态
./chronicle_cleanup.sh monitor

# 生成清理报告
./chronicle_cleanup.sh report
```

### 2. system_monitor.sh - 系统监控
全面的系统监控脚本，检查服务健康状态、资源使用情况等。

**主要功能:**
- 服务健康检查
- HA状态监控
- 交易对状态检查
- Chronicle Queue状态监控
- 系统资源监控
- 网络连接检查
- 告警检查

**使用示例:**
```bash
# 实时监控（默认）
./system_monitor.sh

# 生成完整报告
./system_monitor.sh report

# 检查告警信息
./system_monitor.sh alerts

# 检查服务健康状态
./system_monitor.sh health

# 检查HA状态
./system_monitor.sh ha

# 检查交易对状态
./system_monitor.sh symbols

# 指定服务器地址
./system_monitor.sh --host 192.168.1.100 --port 8080 report
```

### 3. ha_switch.sh - 主备切换
主备实例切换管理脚本。

**主要功能:**
- 查看HA状态
- 故障转移
- 计划内切换
- 回切操作
- 强制激活
- 健康检查

**使用示例:**
```bash
# 查看当前HA状态
./ha_switch.sh status

# 故障转移（主实例故障时）
./ha_switch.sh failover

# 计划内切换（主备角色互换）
./ha_switch.sh switchover

# 回切到原主实例
./ha_switch.sh switchback

# 强制激活指定实例
./ha_switch.sh force-activate 192.168.1.101

# 健康检查
./ha_switch.sh health

# 指定主备实例地址
./ha_switch.sh --primary 10.0.1.100 --standby 10.0.1.101 status
```

### 4. deploy.sh - 系统部署
完整的系统部署和管理脚本。

**主要功能:**
- 项目构建
- 环境部署
- 服务管理
- 健康检查
- 日志查看
- 版本回滚

**使用示例:**
```bash
# 部署到生产环境
./deploy.sh deploy prod

# 部署到开发环境
./deploy.sh deploy dev

# 仅构建项目
./deploy.sh build

# 启动服务
./deploy.sh start

# 停止服务
./deploy.sh stop

# 重启服务
./deploy.sh restart

# 查看服务状态
./deploy.sh status

# 健康检查
./deploy.sh health

# 查看日志（最近50行）
./deploy.sh logs

# 查看日志（最近100行）
./deploy.sh logs 100

# 回滚到上一版本
./deploy.sh rollback
```

## 环境变量配置

### 通用环境变量
```bash
# 服务器配置
export MATCHING_HOST="localhost"
export MATCHING_PORT="8080"

# 主备实例配置
export PRIMARY_HOST="192.168.1.100"
export STANDBY_HOST="192.168.1.101"

# 超时配置
export TIMEOUT="30"
```

```bash
# 清理配置
export CHRONICLE_CLEANUP_RETENTION_DAYS="7"
export CHRONICLE_CLEANUP_MAX_SIZE_GB="100"
export CHRONICLE_CLEANUP_KEEP_RECENT_FILES="3"
```

## 配置文件

### application.properties 清理相关配置
```properties
# Chronicle Queue清理配置
chronicle.cleanup.enabled=true
chronicle.cleanup.retention-days=7
chronicle.cleanup.max-size-gb=100
chronicle.cleanup.keep-recent-files=3
chronicle.cleanup.cron=0 0 2 * * ?

# 不同交易对的差异化配置
chronicle.cleanup.btcusdt.retention-days=14
chronicle.cleanup.ethusdt.retention-days=10
chronicle.cleanup.others.retention-days=7
```

## 运维最佳实践

### 1. 日常监控
```bash
# 每日健康检查
./system_monitor.sh alerts

# 每周生成监控报告
./system_monitor.sh report > weekly_report_$(date +%Y%m%d).txt

# 实时监控（在单独终端运行）
./system_monitor.sh monitor
```

### 2. 清理管理
```bash
# 每日检查存储使用情况
./chronicle_cleanup.sh size BTCUSDT
./chronicle_cleanup.sh size ETHUSDT

# 手动触发清理（在存储空间不足时）
./chronicle_cleanup.sh cleanup-all

# 预览清理操作（确认安全后再执行）
./chronicle_cleanup.sh preview BTCUSDT
```

### 3. 主备切换
```bash
# 定期检查HA状态
./ha_switch.sh status

# 计划内维护时的切换
./ha_switch.sh switchover

# 维护完成后的回切
./ha_switch.sh switchback

# 紧急故障时的转移
./ha_switch.sh failover
```

### 4. 部署管理
```bash
# 生产环境部署流程
./deploy.sh stop
./deploy.sh deploy prod
./deploy.sh health

# 快速重启
./deploy.sh restart

# 问题排查
./deploy.sh logs 200
./deploy.sh status

# 紧急回滚
./deploy.sh rollback
```

## 告警和通知

### 关键指标监控
- 服务健康状态
- 磁盘使用率 > 80%
- Chronicle Queue文件大小异常
- 主备切换状态
- 清理任务失败

### 告警脚本集成
```bash
# 在crontab中设置定期检查
# 每5分钟检查一次告警
*/5 * * * * /opt/matching/scripts/system_monitor.sh alerts | grep -q "告警" && echo "撮合系统告警" | mail -s "Matching System Alert" admin@company.com

# 每天凌晨生成报告
0 1 * * * /opt/matching/scripts/system_monitor.sh report > /var/log/matching/daily_report_$(date +\%Y\%m\%d).txt
```

## 故障排查

### 常见问题和解决方案

1. **服务启动失败**
   ```bash
   ./deploy.sh logs 100
   ./deploy.sh status
   ```

2. **Chronicle Queue文件过大**
   ```bash
   ./chronicle_cleanup.sh size BTCUSDT
   ./chronicle_cleanup.sh cleanup BTCUSDT
   ```

3. **主备切换失败**
   ```bash
   ./ha_switch.sh health
   ./ha_switch.sh status
   ./ha_switch.sh force-activate <HOST>
   ```

## 安全注意事项

1. **权限管理**: 确保脚本具有适当的执行权限
2. **备份验证**: 在执行清理操作前确认备份完整性
3. **主备同步**: 切换前确保数据已同步
4. **监控告警**: 设置适当的监控和告警机制
5. **操作日志**: 记录所有运维操作的日志

## 联系信息

如有问题或建议，请联系运维团队。

---

**注意**: 在生产环境中使用这些脚本前，请先在测试环境中充分验证。