package com.example.promptbatch.client;

/**
 * Signals a non-retryable failure from the inference transport (e.g. a {@code 4xx} other
 * than a rate limit). Retrying would not help, so the retry loop fails the prompt
 * immediately instead of consuming retry budget.
 */
public class NonRetryableInferenceException extends RuntimeException {

    public NonRetryableInferenceException(String message) {
        super(message);
    }
}
