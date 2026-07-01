package com.adamblakey.uptimererer.state;

import com.adamblakey.uptimererer.events.SiteConfig;
import com.adamblakey.uptimererer.probe.ProbeResult;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Pure up/down state-transition rules for a site.
 *
 * <p>A success resets the failure streak and flips the site UP; failures only
 * flip it DOWN once the streak reaches the site's {@code failuresBeforeDown}
 * threshold (anti-flapping).
 */
public final class StateTransitions {

    private StateTransitions() {
    }

    /** Formats an instant as the ISO-8601 string stored in {@code lastCheckedAt}. */
    public static String formatTimestamp(Instant when) {
        return DateTimeFormatter.ISO_INSTANT.format(when);
    }

    /**
     * Applies one probe result to the previous record (null for a never-checked
     * site) and returns the next record.
     */
    public static StateRecord transition(StateRecord previous, SiteConfig site, ProbeResult result, Instant now) {
        String lastCheckedAt = formatTimestamp(now);
        Status previousStatus = (previous == null || previous.status() == null)
                ? Status.UNKNOWN
                : previous.status();
        int previousFailures = previous == null ? 0 : previous.consecutiveFailures();
        String previousChangeAt = previous == null ? null : previous.lastStatusChangeAt();

        if (result.up()) {
            String changeAt = previousStatus == Status.UP ? previousChangeAt : lastCheckedAt;
            return new StateRecord(site.id(), Status.UP, 0, lastCheckedAt, changeAt,
                    result.httpStatus(), result.latencyMillis(), null);
        }

        int failures = previousFailures + 1;
        Status status = previousStatus;
        String changeAt = previousChangeAt;
        if (status != Status.DOWN && failures >= site.failuresBeforeDown()) {
            status = Status.DOWN;
            changeAt = lastCheckedAt;
        }
        String lastError = (result.error() == null || result.error().isEmpty()) ? null : result.error();
        return new StateRecord(site.id(), status, failures, lastCheckedAt, changeAt,
                result.httpStatus(), result.latencyMillis(), lastError);
    }
}
