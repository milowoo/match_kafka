package com.matching.ha;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * HA health monitor - runs on Standby instance.
 * Periodically checks Active peer's health. If Active is unreachable -> log ALERT for operations team.
 * NO auto-failover - switching is manual only.
 */
@Component
@Slf4j
public class HaMonitor {

    @Value("${matching.ha.enabled:false}")
    private boolean haEnabled;

    @Value("${matching.ha.peer-url:}")
    private String peerUrl;

    @Value("${matching.ha.failover-threshold:3}")
    private int failoverThreshold;

    @Value("${matching.ha.health-check-timeout-ms:2000}")
    private int healthCheckTimeoutMs;

    private final InstanceLeaderElection leaderElection;
    private int consecutiveFailures = 0;

    public HaMonitor(InstanceLeaderElection leaderElection) {
        this.leaderElection = leaderElection;
    }

    @Scheduled(fixedDelayString = "${matching.ha.check-interval-ms:10000}")
    public void checkPeerHealth() {
        if (!haEnabled || peerUrl.isEmpty()) return;
        if (leaderElection.getState() != InstanceLeaderElection.State.STANDBY) {
            consecutiveFailures = 0;
            return;
        }
        boolean peerActive = checkPeerIsActive(peerUrl);

        if (peerActive) {
            if (consecutiveFailures > 0) {
                log.info("[HA-Monitor] Peer recovered, resetting failure count");
                consecutiveFailures = 0;
            }
        } else {
            consecutiveFailures++;
            log.warn("[HA-Monitor] Peer health check failed ({}/{}), url: {}",
                    consecutiveFailures, failoverThreshold, peerUrl);

            if (consecutiveFailures >= failoverThreshold) {
                log.error("[HA-ALERT] Active peer unreachable for {} consecutive checks! " +
                                "Manual failover required: POST /ops/ha/activate on this instance",
                        consecutiveFailures);
                // Reset to avoid flooding alerts every 2s
                consecutiveFailures = 0;
            }
        }
    }

    /**
     * Check if remote peer is active by calling its /ops/ha/status endpoint
     */
    private boolean checkPeerIsActive(String baseUrl) {
        try {
            URL url = new URL(baseUrl + "/ops/ha/status");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(healthCheckTimeoutMs);
            conn.setReadTimeout(healthCheckTimeoutMs);

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return false;
            }

            String body = new String(conn.getInputStream().readAllBytes());
            conn.disconnect();
            // Check if response body contains state: "ACTIVE"
            return body.contains("\"state\":\"ACTIVE\"");
        } catch (Exception e) {
            return false;
        }
    }
}