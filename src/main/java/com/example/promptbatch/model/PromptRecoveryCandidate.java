package com.example.promptbatch.model;

/**
 * A prompt found by {@code BatchRepository#staleUnfinishedPrompts} during a background
 * reconciliation sweep: it belongs to a batch that hasn't finished, has no result yet, and
 * either was never picked up or hasn't been touched recently enough to still trust whichever
 * worker/container last claimed it. {@code attemptCount} is how many times it's already been
 * submitted (initial submit + prior retries), which is what {@code StaleTaskRecoveryService}
 * uses to decide whether to retry again or give up.
 */
public record PromptRecoveryCandidate(String batchId, Prompt prompt, int attemptCount) {
}
