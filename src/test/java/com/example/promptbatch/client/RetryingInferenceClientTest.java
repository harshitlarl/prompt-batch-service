package com.example.promptbatch.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.promptbatch.config.RetryConfig;
import com.example.promptbatch.model.Prompt;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Core retry/backoff tests called out explicitly by SOLUTIONING.md §5 - deterministic (no real
 * sleeping) via an injected {@link Sleeper}.
 */
class RetryingInferenceClientTest {

    private final RetryConfig config = new RetryConfig(); // maxRetries=4, base=500, max=5000

    @Test
    void succeedsAfterTransientRateLimiting() {
        List<Long> delays = new ArrayList<>();
        Sleeper fakeSleeper = delays::add;

        InferenceClient transport = mock(InferenceClient.class);
        when(transport.infer(any()))
                .thenThrow(new RateLimitedException("429"))
                .thenThrow(new RateLimitedException("429"))
                .thenReturn(new InferenceResponse("ok"));

        RetryingInferenceClient client = new RetryingInferenceClient(
                transport, config, new ExponentialJitterBackoff(config, new Random(1)),
                new NoopRateLimiter(), fakeSleeper);

        InferenceResponse response = client.infer(new Prompt("p1", "hi"));

        assertThat(response.output()).isEqualTo("ok");
        verify(transport, times(3)).infer(any());
        assertThat(delays).hasSize(2);
    }

    @Test
    void failsPromptAfterMaxRetriesButDoesNotThrowUnboundedly() {
        InferenceClient transport = mock(InferenceClient.class);
        when(transport.infer(any())).thenThrow(new RateLimitedException("429"));

        RetryingInferenceClient client = new RetryingInferenceClient(
                transport, config, new ExponentialJitterBackoff(config, new Random(1)),
                new NoopRateLimiter(), millis -> { });

        assertThatThrownBy(() -> client.infer(new Prompt("p1", "hi")))
                .isInstanceOf(RateLimitedException.class)
                .hasMessageContaining("retries exhausted");
        verify(transport, times(config.getMaxRetries() + 1)).infer(any());
    }

    @Test
    void nonRetryableFailureIsNotRetried() {
        InferenceClient transport = mock(InferenceClient.class);
        when(transport.infer(any())).thenThrow(new NonRetryableInferenceException("400 bad request"));

        RetryingInferenceClient client = new RetryingInferenceClient(
                transport, config, new ExponentialJitterBackoff(config, new Random(1)),
                new NoopRateLimiter(), millis -> { });

        assertThatThrownBy(() -> client.infer(new Prompt("p1", "hi")))
                .isInstanceOf(NonRetryableInferenceException.class);
        verify(transport, times(1)).infer(any());
    }

    @Test
    void oneExhaustedPromptDoesNotAffectASiblingPromptSucceeding() {
        InferenceClient transport = mock(InferenceClient.class);
        Prompt failing = new Prompt("p-fail", "x");
        Prompt succeeding = new Prompt("p-ok", "y");
        when(transport.infer(failing)).thenThrow(new RateLimitedException("429"));
        when(transport.infer(succeeding)).thenReturn(new InferenceResponse("ok"));

        RetryingInferenceClient client = new RetryingInferenceClient(
                transport, config, new ExponentialJitterBackoff(config, new Random(1)),
                new NoopRateLimiter(), millis -> { });

        assertThatThrownBy(() -> client.infer(failing)).isInstanceOf(RateLimitedException.class);
        assertThat(client.infer(succeeding).output()).isEqualTo("ok");
    }

    @Test
    void acquiresFromRateLimiterBeforeEveryAttempt() {
        InferenceClient transport = mock(InferenceClient.class);
        when(transport.infer(any()))
                .thenThrow(new RateLimitedException("429"))
                .thenReturn(new InferenceResponse("ok"));
        RateLimiter limiter = mock(RateLimiter.class);

        RetryingInferenceClient client = new RetryingInferenceClient(
                transport, config, new ExponentialJitterBackoff(config, new Random(1)),
                limiter, millis -> { });

        client.infer(new Prompt("p1", "hi"));

        verify(limiter, times(2)).acquire();
    }

    @Test
    void interruptDuringBackoffAbortsAndRestoresInterruptFlag() {
        InferenceClient transport = mock(InferenceClient.class);
        when(transport.infer(any())).thenThrow(new RateLimitedException("429"));
        Sleeper interruptingSleeper = millis -> {
            throw new InterruptedException("simulated interrupt");
        };

        RetryingInferenceClient client = new RetryingInferenceClient(
                transport, config, new ExponentialJitterBackoff(config, new Random(1)),
                new NoopRateLimiter(), interruptingSleeper);

        try {
            assertThatThrownBy(() -> client.infer(new Prompt("p1", "hi")))
                    .isInstanceOf(RateLimitedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clear the flag so it doesn't leak into other tests
        }
    }
}
