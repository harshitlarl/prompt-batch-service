package com.example.promptbatch.client;

import com.example.promptbatch.config.RetryConfig;
import com.example.promptbatch.model.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator that adds retry + exponential backoff + jitter around a raw {@link InferenceClient}
 * transport (SOLUTIONING.md §4.4, LLD.md §5.4).
 *
 * <p>The retry <em>loop</em> lives here; the delay <em>math</em> is delegated to a
 * {@link BackoffStrategy}, the <em>waiting</em> to a {@link Sleeper}, and the offered-rate
 * gating to a {@link RateLimiter} - each swappable independently.
 */
public class RetryingInferenceClient implements InferenceClient {

    private static final Logger LOG = LoggerFactory.getLogger(RetryingInferenceClient.class);

    private final InferenceClient delegate;
    private final RetryConfig config;
    private final BackoffStrategy backoff;
    private final Sleeper sleeper;
    private final RateLimiter rateLimiter;

    public RetryingInferenceClient(
            InferenceClient delegate,
            RetryConfig config,
            BackoffStrategy backoff,
            RateLimiter rateLimiter,
            Sleeper sleeper) {
        this.delegate = delegate;
        this.config = config;
        this.backoff = backoff;
        this.rateLimiter = rateLimiter;
        this.sleeper = sleeper;
    }

    @Override
    public InferenceResponse infer(Prompt prompt) {
        int attempt = 0;
        while (true) {
            rateLimiter.acquire();
            try {
                return delegate.infer(prompt);
            } catch (RateLimitedException e) {
                if (attempt >= config.getMaxRetries()) {
                    throw new RateLimitedException(
                            "retries exhausted after " + (attempt + 1) + " attempts for prompt "
                                    + prompt.id() + ": " + e.getMessage(),
                            e);
                }
                long delay = backoff.delayMillis(attempt);
                LOG.warn(
                        "Rate limited on prompt {} (attempt {}/{}), backing off {}ms",
                        prompt.id(),
                        attempt + 1,
                        config.getMaxRetries() + 1,
                        delay);
                sleepQuietly(delay);
                attempt++;
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RateLimitedException("interrupted during backoff", ie);
        }
    }
}
