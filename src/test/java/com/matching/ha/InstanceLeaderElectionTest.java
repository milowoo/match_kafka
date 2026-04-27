package com.matching.ha;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class InstanceLeaderElectionTest {

    private InstanceLeaderElection election;

    @BeforeEach
    void setUp() throws Exception {
        election = new InstanceLeaderElection();
        // Enable HA via reflection
        var haField = InstanceLeaderElection.class.getDeclaredField("haEnabled");
        haField.setAccessible(true);
        haField.set(election, true);

        var idField = InstanceLeaderElection.class.getDeclaredField("instanceId");
        idField.setAccessible(true);
        idField.set(election, "test-node");
    }

    @Test
    @DisplayName("Initial state is STANDBY")
    void testInitialState() {
        assertEquals(InstanceLeaderElection.State.STANDBY, election.getState());
        assertFalse(election.isActive());
    }

    @Test
    @DisplayName("STANDBY -> ACTIVE via activate()")
    void testActivate() {
        assertTrue(election.activate());
        assertEquals(InstanceLeaderElection.State.ACTIVE, election.getState());
        assertTrue(election.isActive());
    }

    @Test
    @DisplayName("Cannot activate from ACTIVE state")
    void testActivateFromActive() {
        election.activate();
        assertFalse(election.activate());
        assertEquals(InstanceLeaderElection.State.ACTIVE, election.getState());
    }

    @Test
    @DisplayName("Cannot activate from DRAINING state")
    void testActivateFromDraining() {
        election.activate();
        election.startDeactivate();
        assertFalse(election.activate());
        assertEquals(InstanceLeaderElection.State.DRAINING, election.getState());
    }

    @Test
    @DisplayName("ACTIVE -> DRAINING via startDeactivate()")
    void testStartDeactivate() {
        election.activate();
        assertTrue(election.startDeactivate());
        assertEquals(InstanceLeaderElection.State.DRAINING, election.getState());
        assertFalse(election.isActive());
    }

    @Test
    @DisplayName("Cannot deactivate from STANDBY state")
    void testDeactivateFromStandby() {
        assertFalse(election.startDeactivate());
        assertEquals(InstanceLeaderElection.State.STANDBY, election.getState());
    }

    @Test
    @DisplayName("DRAINING -> STANDBY via completeDeactivate()")
    void testCompleteDeactivate() {
        election.activate();
        election.startDeactivate();
        election.completeDeactivate();
        assertEquals(InstanceLeaderElection.State.STANDBY, election.getState());
        assertFalse(election.isActive());
    }

    @Test
    @DisplayName("Full cycle: STANDBY -> ACTIVE -> DRAINING -> STANDBY -> ACTIVE")
    void testFullCycle() {
        assertEquals(InstanceLeaderElection.State.STANDBY, election.getState());

        assertTrue(election.activate());
        assertEquals(InstanceLeaderElection.State.ACTIVE, election.getState());

        assertTrue(election.startDeactivate());
        assertEquals(InstanceLeaderElection.State.DRAINING, election.getState());

        election.completeDeactivate();
        assertEquals(InstanceLeaderElection.State.STANDBY, election.getState());

        assertTrue(election.activate());
        assertEquals(InstanceLeaderElection.State.ACTIVE, election.getState());
    }

    @Test
    @DisplayName("HA disabled: isActive always true")
    void testHaDisabled() throws Exception {
        var haField = InstanceLeaderElection.class.getDeclaredField("haEnabled");
        haField.setAccessible(true);
        haField.set(election, false);

        assertTrue(election.isActive());
        assertTrue(election.activate()); // no-op but returns true
    }
}