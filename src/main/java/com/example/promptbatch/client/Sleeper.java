package com.example.promptbatch.client;

/**
 * Ambient "wait this long" concern, injected so retry/backoff logic is deterministically
 * unit-testable (no real sleeping in tests).
 */
@FunctionalInterface
public interface Sleeper {

    void sleep(long millis) throws InterruptedException;

    Sleeper REAL = Thread::sleep;
}
