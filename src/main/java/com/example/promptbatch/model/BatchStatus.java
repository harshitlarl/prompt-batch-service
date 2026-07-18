package com.example.promptbatch.model;

/**
 * Lifecycle of a {@link Batch}. Transitions are monotonic: {@code QUEUED -> PROCESSING ->
 * {COMPLETED|FAILED}}. There is no back-transition.
 */
public enum BatchStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED
}
