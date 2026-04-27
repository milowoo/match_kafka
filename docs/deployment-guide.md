# 撮合系统部署指南

## 概述

本文档描述了撮合系统 JDK 21 + Spring Boot 3.5.7 + Chronicle Queue 版本的部署流程。

## 系统要求

### 硬件要求
- **CPU**: 8核心以上，推荐16核心
- **内存**: 16GB以上，推荐32GB
- **磁盘**: SSD 500GB以上，推荐1TB NVMe SSD
- **网络**: 千兆网卡

### 软件要求
- **操作系统**: Linux (CentOS 7+, Ubuntu 18.04+)
- **JDK**: OpenJDK 21 或 Oracle JDK 21
- **Maven**: 3.8.0+
- **Redis**: 6.0+
- **Kafka**: 2.8.0+

## 环境准备

### 1. 安装JDK 21
```bash
# CentOS/RHEL
sudo yum install java-21-openjdk java-21-openjdk-devel

# Ubuntu/Debian
sudo apt update
sudo apt install openjdk-21-jdk

# 验证安装
java -version
javac -version
```

### 2. 安装Maven
```bash
# 下载并安装Maven
wget https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz
sudo tar -xzf apache-maven-3.9.6-bin.tar.gz -C /opt/
sudo ln -s /opt/apache-maven-3.9.6 /opt/maven

# 配置环境变量
echo 'export MAVEN_HOME=/opt/maven' >> ~/.bashrc
echo 'export PATH=$MAVEN_HOME/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
```

### 3. 配置系统参数
```bash
# 增加文件描述符限制
echo '* soft nofile 65536' >> /etc/security/limits.conf
echo '* hard nofile 65536' >> /etc/security/limits.conf

# 配置内核参数
echo 'vm.max_map_count=262144' >> /etc/sysctl.conf
echo 'net.core.somaxconn=65535' >> /etc/sysctl.conf
sysctl -p
```

## 部署流程

### 1. 获取源码
```bash
# 克隆项目
git clone <repository-url> /opt/matching
cd /opt/matching
```

### 2. 构建项目
```bash
# 编译项目
mvn clean package -DskipTests

# 验证构建结果
ls -la target/matching.jar
```

### 3. 使用部署脚本

#### 开发环境部署
```bash
cd sh
chmod +x deploy.sh
./deploy.sh dev
```

#### 测试环境部署
```bash
./deploy.sh test
```

#### 生产环境部署
```bash
./deploy.sh prod
```

### 4. 手动部署步骤

如果不使用自动化脚本，可以按以下步骤手动部署：

#### 创建目录结构
```bash
sudo mkdir -p /opt/matching/{bin,config,logs,data,scripts}
sudo mkdir -p /var/log/matching
sudo chown -R $USER:$USER /opt/matching
sudo chown -R $USER:$USER /var/log/matching
```

#### 复制文件
```bash
# 复制JAR文件
cp target/matching.jar /opt/matching/bin/

# 复制配置文件
cp src/main/resources/application.properties /opt/matching/config/

# 复制脚本
cp sh/*.sh /opt/matching/scripts/
chmod +x /opt/matching/scripts/*.sh
```

#### 创建systemd服务
```bash
sudo tee /etc/systemd/system/matching.service > /dev/null <<EOF
[Unit]
Description=Matching Engine Service
After=network.target

[Service]
Type=simple
User=$USER
Group=$USER
WorkingDirectory=/opt/matching
ExecStart=/usr/bin/java \$JAVA_OPTS -jar /opt/matching/bin/matching.jar --spring.config.location=/opt/matching/config/application.properties
ExecStop=/bin/kill -TERM \$MAINPID
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=matching

# JVM参数
Environment="JAVA_OPTS=-Xms2g -Xmx4g -XX:+UseG1GC -XX:+UseStringDeduplication"

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable matching
```

## 配置管理

### 1. 应用配置
主要配置文件位于 `/opt/matching/config/application.properties`

#### 核心配置项
```properties
# 服务端口
server.port=8080

# Chronicle Queue配置
matching.eventlog.chronicle.path=/opt/matching/data/eventlog-queue
matching.eventlog.chronicle.rollCycle=HOURLY
matching.eventlog.chronicle.blockSize=64MB

# Redis配置
spring.redis.host=localhost
spring.redis.port=6379
spring.redis.database=0

# Kafka配置
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.consumer.group-id=matching-group
```

### 2. JVM参数调优
```bash
# 生产环境推荐JVM参数
JAVA_OPTS="-Xms4g -Xmx8g \
  -XX:+UseG1GC \
  -XX:+UseStringDeduplication \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1HeapRegionSize=16m \
  -XX:+UnlockExperimentalVMOptions \
  -XX:+UseTransparentHugePages \
  -Djava.awt.headless=true"
```

## 服务管理

### 启动服务
```bash
sudo systemctl start matching
```

### 停止服务
```bash
sudo systemctl stop matching
```

### 重启服务
```bash
sudo systemctl restart matching
```

### 查看状态
```bash
sudo systemctl status matching
```

### 查看日志
```bash
# 查看systemd日志
sudo journalctl -u matching -f

# 查看应用日志
tail -f /var/log/matching/matching.log
```

## 健康检查

### 1. 服务健康检查
```bash
# 检查服务状态
curl http://localhost:8080/actuator/health

# 检查版本信息
curl http://localhost:8080/ops/version

# 检查HA状态
curl http://localhost:8080/ops/ha/status
```

### 2. 性能监控
```bash
# 检查JVM指标
curl http://localhost:8080/actuator/metrics

# 检查Chronicle Queue指标
curl http://localhost:8080/actuator/metrics/chronicle.queue
```

## 故障排查

### 1. 常见问题

#### 服务启动失败
```bash
# 检查Java版本
java -version

# 检查端口占用
netstat -tlnp | grep 8080

# 检查磁盘空间
df -h

# 查看详细错误日志
sudo journalctl -u matching -n 100
```

#### Chronicle Queue问题
```bash
# 检查队列目录权限
ls -la /opt/matching/data/eventlog-queue/

# 检查磁盘空间
du -sh /opt/matching/data/

# 清理旧队列文件（谨慎操作）
./scripts/chronicle_cleanup.sh
```

### 2. 日志分析
```bash
# 查看错误日志
grep ERROR /var/log/matching/matching.log

# 查看性能日志
grep "Performance" /var/log/matching/matching.log

# 查看GC日志
grep "GC" /var/log/matching/matching.log
```

## 升级部署

### 1. 滚动升级
```bash
# 停止服务
sudo systemctl stop matching

# 备份当前版本
cp /opt/matching/bin/matching.jar /opt/matching/bin/matching.jar.backup

# 部署新版本
cp target/matching.jar /opt/matching/bin/

# 启动服务
sudo systemctl start matching

# 健康检查
curl http://localhost:8080/actuator/health
```

### 2. 回滚操作
```bash
# 使用部署脚本回滚
./sh/deploy.sh rollback

# 或手动回滚
sudo systemctl stop matching
cp /opt/matching/bin/matching.jar.backup /opt/matching/bin/matching.jar
sudo systemctl start matching
```

## 监控告警

### 1. 系统监控
- CPU使用率 < 80%
- 内存使用率 < 85%
- 磁盘使用率 < 90%
- 网络连接数正常

### 2. 应用监控
- 服务健康状态：UP
- 响应时间 < 100ms
- TPS监控
- 错误率 < 0.1%

### 3. Chronicle Queue监控
- 队列写入延迟 < 1ms
- 队列大小监控
- 磁盘空间监控

## 安全配置

### 1. 网络安全
```bash
# 配置防火墙
sudo firewall-cmd --permanent --add-port=8080/tcp
sudo firewall-cmd --reload
```

### 2. 文件权限
```bash
# 设置适当的文件权限
chmod 750 /opt/matching/bin/matching.jar
chmod 640 /opt/matching/config/application.properties
```

## 性能调优

### 1. 系统级调优
```bash
# 网络参数优化
echo 'net.core.rmem_max = 134217728' >> /etc/sysctl.conf
echo 'net.core.wmem_max = 134217728' >> /etc/sysctl.conf
echo 'net.ipv4.tcp_rmem = 4096 87380 134217728' >> /etc/sysctl.conf
echo 'net.ipv4.tcp_wmem = 4096 65536 134217728' >> /etc/sysctl.conf
sysctl -p
```

### 2. 应用级调优
- 合理设置JVM堆大小
- 启用G1垃圾收集器
- 配置Chronicle Queue参数
- 优化线程池大小

## 备份恢复

### 1. 数据备份
```bash
# 备份Chronicle Queue数据
tar -czf matching-data-$(date +%Y%m%d).tar.gz /opt/matching/data/

# 备份配置文件
tar -czf matching-config-$(date +%Y%m%d).tar.gz /opt/matching/config/
```

### 2. 数据恢复
```bash
# 停止服务
sudo systemctl stop matching

# 恢复数据
tar -xzf matching-data-20240101.tar.gz -C /

# 启动服务
sudo systemctl start matching
```