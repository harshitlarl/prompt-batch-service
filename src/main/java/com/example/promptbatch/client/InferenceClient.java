package com.example.promptbatch.client;

import com.example.promptbatch.model.Prompt;

/**
 * Seam S1 (LLD.md §4): performs one inference call for one prompt.
 *
 * <p>Implementations may throw {@link RateLimitedException} (retryable) or
 * {@link NonRetryableInferenceException} (not retryable). Composed with a
 * {@code RetryingInferenceClient} decorator to add resilience without changing callers.
 */
public interface InferenceClient {

    InferenceResponse infer(Prompt prompt);
}
