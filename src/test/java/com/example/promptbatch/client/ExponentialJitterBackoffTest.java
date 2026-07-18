package com.example.promptbatch.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.promptbatch.config.RetryConfig;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ExponentialJitterBackoffTest {

    @Test
    void growsExponentiallyAndCapsAtMaxDelayWhenJitterDisabled() {
        RetryConfig config = new RetryConfig();
        config.setBaseDelayMs(500);
        config.setMaxDelayMs(5_000);
        config.setJitter(false);
        BackoffStrategy backoff = new ExponentialJitterBackoff(config, new Random(1));

        assertThat(backoff.delayMillis(0)).isEqualTo(500);
        assertThat(backoff.delayMillis(1)).isEqualTo(1_000);
        assertThat(backoff.delayMillis(2)).isEqualTo(2_000);
        assertThat(backoff.delayMillis(3)).isEqualTo(4_000);
        assertThat(backoff.delayMillis(4)).isEqualTo(5_000); // capped
        assertThat(backoff.delayMillis(10)).isEqualTo(5_000); // still capped
    }

    @Test
    void jitteredDelayIsWithinBoundsOfUncappedDelay() {
        RetryConfig config = new RetryConfig();
        config.setBaseDelayMs(500);
        config.setMaxDelayMs(5_000);
        config.setJitter(true);
        BackoffStrategy backoff = new ExponentialJitterBackoff(config, new Random(42));

        for (int attempt = 0; attempt < 6; attempt++) {
            long delay = backoff.delayMillis(attempt);
            assertThat(delay).isPositive();
            assertThat(delay).isLessThanOrEqualTo(5_000);
        }
    }

    @Test
    void neverExceedsMaxDelayEvenForLargeAttemptCounts() {
        RetryConfig config = new RetryConfig();
        config.setBaseDelayMs(500);
        config.setMaxDelayMs(5_000);
        config.setJitter(true);
        BackoffStrategy backoff = new ExponentialJitterBackoff(config, new Random(7));

        assertThat(backoff.delayMillis(60)).isLessThanOrEqualTo(5_000);
    }
}
