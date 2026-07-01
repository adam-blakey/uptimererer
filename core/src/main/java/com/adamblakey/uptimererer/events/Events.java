package com.adamblakey.uptimererer.events;

/**
 * EventBridge routing metadata for check requests, shared between the
 * dispatcher (which publishes) and the checker (which is triggered).
 */
public final class Events {

    /** {@code source} field on every CheckRequested event. */
    public static final String SOURCE = "uptimererer.dispatcher";

    /** {@code detail-type} field on every CheckRequested event. */
    public static final String DETAIL_TYPE_CHECK_REQUESTED = "CheckRequested";

    private Events() {
    }
}
