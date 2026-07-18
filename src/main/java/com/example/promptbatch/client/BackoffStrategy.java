package com.example.promptbatch.client;

/**
 * Seam S2 (LLD.md §4): computes the delay before retry attempt {@code attempt} (0-indexed,
 * the attempt number that just failed). A pure function - no sleeping - so it is trivially
 * unit-testable in isolation from the retry loop.
 */
public interface BackoffStrategy {

    long delayMillis(int attempt);
}
