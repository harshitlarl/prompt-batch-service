package com.example.promptbatch.client;

import com.example.promptbatch.config.RetryConfig;
import java.util.Random;

/**
 * Bounded exponential backoff with full jitter: {@code delay = min(base * 2^attempt, maxDelay)},
 * then, unless jitter is disabled, a uniform random value in {@code (0, delay]}.
 *
 * <p>Full jitter avoids the thundering herd: without it, many workers that hit {@code 429} at
 * the same instant would retry in lockstep and re-trigger the limit (SOLUTIONING.md §4.4).
 */
public class ExponentialJitterBackoff implements BackoffStrategy {

    private final RetryConfig config;
    private final Random random;

    public ExponentialJitterBackoff(RetryConfig config, Random random) {
        this.config = config;
        this.random = random;
    }

    @Override
    public long delayMillis(int attempt) {
        long exponential = config.getBaseDelayMs() * (1L << Math.min(attempt, 62));
        long capped = Math.min(exponential, config.getMaxDelayMs());
        if (capped < 0) {
            // overflow guard for very large attempt counts
            capped = config.getMaxDelayMs();
        }
        if (!config.isJitter()) {
            return capped;
        }
        return 1 + (long) (random.nextDouble() * capped);
    }
}
