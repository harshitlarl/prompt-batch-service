package com.example.promptbatch.client;

/**
 * Signals a retryable failure from the inference transport: an HTTP {@code 429} (or a
 * retryable {@code 5xx}), or - once retries are exhausted - the terminal "gave up" failure
 * for a single prompt. The batch itself is never failed by this exception; callers (workers)
 * turn it into a {@code PromptResult.failure(...)}.
 */
public class RateLimitedException extends RuntimeException {

    public RateLimitedException(String message) {
        super(message);
    }

    public RateLimitedException(String message, Throwable cause) {
        super(message, cause);
    }
}
