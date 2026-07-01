package com.adamblakey.uptimererer.probe;

import java.time.Duration;

/**
 * Outcome of one probe.
 *
 * @param up         true iff the request completed with an expected status
 * @param httpStatus the response status, or 0 when no response was received
 * @param latency    time to the (possibly failed) response
 * @param error      description of the failure, or "" when {@code up}
 */
public record ProbeResult(boolean up, int httpStatus, Duration latency, String error) {

    /** Latency in whole milliseconds (0 when unknown). */
    public long latencyMillis() {
        return latency == null ? 0 : latency.toMillis();
    }
}
