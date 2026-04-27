package com.matching.ha;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Manual Active-Standby switch for 2-instance deployment.
 * Both instances start as STANDBY. Operator sends command to activate one.
 * No automatic failover — fully controlled by operations team.
 * States: STANDBY → ACTIVE → DRAINING → STANDBY
 */
@Component
public class InstanceLeaderElection {
    private static final Logger log = LoggerFactory.getLogger(InstanceLeaderElection.class);
    public enum State { STANDBY, ACTIVE, DRAINING }

    @Value("${matching.ha.instance-id:node-1}")
    private String instanceId;

    @Value("${matching.ha.enabled:false}")
    private boolean haEnabled;

    private final AtomicReference<State> state = new AtomicReference<>(State.STANDBY);

    public boolean isHaEnabled() { return haEnabled; }
    public String getInstanceId() { return instanceId; }
    public State getState() { return state.get(); }

    /**
     * If HA disabled, always Active (single-instance mode).
     */
    public boolean isActive() { return !haEnabled || state.get() == State.ACTIVE; }

    /**
     * Operator command: STANDBY → ACTIVE. Returns false if not in STANDBY state.
     */
    public boolean activate() {
        if (!haEnabled) return true;
        boolean success = state.compareAndSet(State.STANDBY, State.ACTIVE);
        if (success) {
            log.info("[HA] Instance {} switched to ACTIVE", instanceId);
        } else {
            log.warn("[HA] Cannot activate, current state: {}", state.get());
        }
        return success;
    }

    /**
     * Operator command: ACTIVE → DRAINING → STANDBY.
     * Caller must drain Disruptors between DRAINING and STANDBY.
     * Returns false if not in ACTIVE state.
     */
    public boolean startDeactivate() {
        if (!haEnabled) return false;
        boolean success = state.compareAndSet(State.ACTIVE, State.DRAINING);
        if (success) {
            log.info("[HA] Instance {} switching to DRAINING", instanceId);
        } else {
            log.warn("[HA] Cannot deactivate, current state: {}", state.get());
        }
        return success;
    }

    /**
     * Complete deactivation: DRAINING → STANDBY.
     * Called after Disruptors are fully drained.
     */
    public void completeDeactivate() {
        state.set(State.STANDBY);
        log.info("[HA] Instance {} switched to STANDBY", instanceId);
    }
}