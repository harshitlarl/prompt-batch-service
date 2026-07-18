package com.example.promptbatch.client;

/**
 * Seam S7 (LLD.md §4): gates the *offered* request rate before an inference attempt.
 *
 * <p>v1 wires a {@link NoopRateLimiter}. At scale, the same call site swaps in a distributed
 * token bucket (e.g. backed by Redis) - callers never change.
 */
public interface RateLimiter {

    /** Blocks (or returns immediately) until a slot is available. */
    void acquire();
}
