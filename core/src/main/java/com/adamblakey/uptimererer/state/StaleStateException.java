package com.adamblakey.uptimererer.state;

/**
 * Thrown by {@link StateRepository#putIfUnchanged} when the stored item changed
 * between read and write — i.e. a concurrent invocation already wrote a newer
 * result, so this one should be discarded.
 */
public class StaleStateException extends Exception {

    public StaleStateException(String message) {
        super(message);
    }
}
