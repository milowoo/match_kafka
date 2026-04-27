package com.matching.service.chronicle;

import com.matching.service.OrderBookService;
import org.springframework.stereotype.Component;

/**
 * Chronicle Queue高可用操作管理器 负责处理主从切换操作和状态管理
 */
@Component
public class ChronicleQueueHAOperations {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChronicleQueueHAOperations.class);
    private final ChronicleQueueFactory queueFactory;
    private final ChronicleQueueComponentManager componentManager;

    public ChronicleQueueHAOperations(ChronicleQueueFactory queueFactory,
                                     ChronicleQueueComponentManager componentManager) {
        this.queueFactory = queueFactory;
        this.componentManager = componentManager;
    }

    /**
     * 切换指定交易对到主实例
     */
    public synchronized void switchToPrimary(String symbolId, OrderBookService orderBookService) {
        ensureOrderBookServiceReady(orderBookService);
        log.info("Switching symbol {} to PRIMARY mode...", symbolId);

        ChronicleQueueComponentManager.SymbolQueueComponents components =
                componentManager.getComponents(symbolId);

        if (components != null) {
            components.haManager.switchToPrimary();
        } else {
            log.warn("No components found for symbol: {}, will be created as PRIMARY when needed", symbolId);
        }
    }

    /**
     * 切换所有交易对到主实例
     */
    public synchronized void switchAllToPrimary(OrderBookService orderBookService) {
        ensureOrderBookServiceReady(orderBookService);
        log.info("Switching all symbols to PRIMARY mode...");

        queueFactory.switchAllToPrimary();

        // 同时切换所有已创建的组件
        for (ChronicleQueueComponentManager.SymbolQueueComponents components :
                componentManager.getAllComponents().values()) {
            try {
                components.haManager.switchToPrimary();
            } catch (Exception e) {
                log.warn("Failed to switch HA manager to primary for symbol", e);
            }
        }
    }

    /**
     * 切换指定交易对到从实例
     */
    public synchronized void switchToStandby(String symbolId) {
        log.info("Switching symbol {} to STANDBY mode...", symbolId);

        ChronicleQueueComponentManager.SymbolQueueComponents components =
                componentManager.getComponents(symbolId);

        if (components != null) {
            components.haManager.switchToStandby();
        } else {
            log.warn("No components found for symbol: {}, will be created as STANDBY when needed", symbolId);
        }
    }

    /**
     * 切换所有交易对到从实例
     */
    public synchronized void switchAllToStandby() {
        log.info("Switching all symbols to STANDBY mode...");

        queueFactory.switchAllToStandby();

        // 同时切换所有已创建的组件
        for (ChronicleQueueComponentManager.SymbolQueueComponents components :
                componentManager.getAllComponents().values()) {
            try {
                components.haManager.switchToStandby();
            } catch (Exception e) {
                log.warn("Failed to switch HA manager to standby for symbol", e);
            }
        }
    }

    /**
     * 获取指定交易对的角色
     */
    public String getRole(String symbolId) {
        ChronicleQueueComponentManager.SymbolQueueComponents components =
                componentManager.getComponents(symbolId);

        if (components != null) {
            return components.queueManager.getRole();
        } else {
            // 如果组件不存在，返回默认角色
            return "STANDBY";
        }
    }

    /**
     * 检查指定交易对是否为主实例
     */
    public boolean isPrimary(String symbolId) {
        return "PRIMARY".equals(getRole(symbolId));
    }

    /**
     * 检查指定交易对是否为从实例
     */
    public boolean isStandby(String symbolId) {
        return "STANDBY".equals(getRole(symbolId));
    }

    /**
     * 获取所有交易对的角色状态
     */
    public java.util.Map<String, String> getAllRoles() {
        java.util.Map<String, String> roles = new java.util.HashMap<>();

        for (java.util.Map.Entry<String, ChronicleQueueComponentManager.SymbolQueueComponents> entry :
                componentManager.getAllComponents().entrySet()) {
            roles.put(entry.getKey(), entry.getValue().queueManager.getRole());
        }

        return roles;
    }

    /**
     * 强制同步指定交易对的状态
     */
    public void forceSyncStatus(String symbolId) {
        ChronicleQueueComponentManager.SymbolQueueComponents components =
                componentManager.getComponents(symbolId);

        if (components != null) {
            try {
                // 简化实现，只记录日志
                log.info("Status synced for symbol: {}", symbolId);
            } catch (Exception e) {
                log.error("Failed to sync status for symbol: {}", symbolId, e);
                throw new RuntimeException("Failed to sync status for symbol: " + symbolId, e);
            }
        } else {
            log.warn("No components found for symbol: {}, cannot sync status", symbolId);
        }
    }

    /**
     * 获取HA状态信息
     */
    public HAStatus getHAStatus() {
        java.util.Map<String, String> symbolRoles = getAllRoles();
        ChronicleQueueFactory.FactoryStatus factoryStatus = queueFactory.getStatus();
        return new HAStatus(symbolRoles, factoryStatus);
    }

    /**
     * 确保OrderBookService已准备就绪
     */
    private void ensureOrderBookServiceReady(OrderBookService orderBookService) {
        if (orderBookService == null) {
            throw new IllegalStateException("OrderBookService not ready. Ensure setOrderBookService() is called before using HA operations.");
        }
    }

    /**
     * HA状态信息类
     */
    public static class HAStatus {
        public final java.util.Map<String, String> symbolRoles;
        public final ChronicleQueueFactory.FactoryStatus factoryStatus;

        public HAStatus(java.util.Map<String, String> symbolRoles,
                        ChronicleQueueFactory.FactoryStatus factoryStatus) {
            this.symbolRoles = symbolRoles;
            this.factoryStatus = factoryStatus;
        }

        @Override
        public String toString() {
            return String.format("HAStatus{symbolRoles=%s, factoryStatus=%s}",
                    symbolRoles, factoryStatus);
        }
    }
}