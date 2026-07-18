package com.example.promptbatch.client;

/** v1 implementation of {@link RateLimiter}: no global gating, the worker pool size is enough. */
public class NoopRateLimiter implements RateLimiter {

    @Override
    public void acquire() {
        // intentionally a no-op in v1 - see LLD.md §10 for the scaled RedisTokenBucketRateLimiter.
    }
}
