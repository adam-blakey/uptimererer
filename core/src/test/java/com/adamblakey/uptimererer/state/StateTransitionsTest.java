package com.adamblakey.uptimererer.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.adamblakey.uptimererer.events.SiteConfig;
import com.adamblakey.uptimererer.probe.ProbeResult;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StateTransitionsTest {

    private static final SiteConfig SITE = new SiteConfig("example", "https://example.com", null, 0, null, 3);
    private static final ProbeResult UP = new ProbeResult(true, 200, Duration.ofMillis(120), "");
    private static final ProbeResult DOWN = new ProbeResult(false, 0, Duration.ZERO, "connection refused");
    private static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");

    private static StateRecord previous(Status status, int failures) {
        return new StateRecord("example", status, failures, "2026-06-01T00:00:00Z", "earlier", 0, 0, null);
    }

    static Stream<Arguments> transitions() {
        return Stream.of(
                Arguments.of("first check success goes up", null, UP, Status.UP, 0, true),
                Arguments.of("first check failure stays unknown",
                        previous(Status.UNKNOWN, 0), DOWN, Status.UNKNOWN, 1, false),
                Arguments.of("up stays up", previous(Status.UP, 0), UP, Status.UP, 0, false),
                Arguments.of("single failure does not flip up site",
                        previous(Status.UP, 0), DOWN, Status.UP, 1, false),
                Arguments.of("streak below threshold stays up",
                        previous(Status.UP, 1), DOWN, Status.UP, 2, false),
                Arguments.of("streak reaching threshold flips down",
                        previous(Status.UP, 2), DOWN, Status.DOWN, 3, true),
                Arguments.of("unknown site flips down at threshold",
                        previous(Status.UNKNOWN, 2), DOWN, Status.DOWN, 3, true),
                Arguments.of("down stays down without new change timestamp",
                        previous(Status.DOWN, 5), DOWN, Status.DOWN, 6, false),
                Arguments.of("success resets streak mid-flap",
                        previous(Status.UP, 2), UP, Status.UP, 0, false),
                Arguments.of("recovery flips down site up",
                        previous(Status.DOWN, 7), UP, Status.UP, 0, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transitions")
    void applyTransition(String name, StateRecord previous, ProbeResult result,
                         Status wantStatus, int wantFailures, boolean wantChange) {
        StateRecord got = StateTransitions.transition(previous, SITE, result, NOW);

        assertEquals(wantStatus, got.status(), "status");
        assertEquals(wantFailures, got.consecutiveFailures(), "consecutiveFailures");
        assertEquals(StateTransitions.formatTimestamp(NOW), got.lastCheckedAt(), "lastCheckedAt");
        assertEquals("example", got.siteId(), "siteId");
        assertEquals(result.httpStatus(), got.lastHttpStatus(), "lastHttpStatus");

        String expectedChangeAt = wantChange
                ? StateTransitions.formatTimestamp(NOW)
                : (previous == null ? null : previous.lastStatusChangeAt());
        assertEquals(expectedChangeAt, got.lastStatusChangeAt(), "lastStatusChangeAt");

        if (result.up()) {
            assertNull(got.lastError(), "lastError cleared on success");
        } else {
            assertEquals(result.error(), got.lastError(), "lastError carried over");
        }
    }

    @Test
    void formatTimestampIsIso8601() {
        assertEquals("2026-07-01T12:00:00Z", StateTransitions.formatTimestamp(NOW));
    }
}
