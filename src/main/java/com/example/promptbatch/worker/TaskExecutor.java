package com.example.promptbatch.worker;

import io.dropwizard.lifecycle.Managed;

/**
 * Seam S6 (LLD.md §4): abstracts "how work runs". v1 wires a fixed-size thread pool; at scale
 * the same call site becomes a durable-queue consumer with a per-pod bounded pool underneath
 * (LLD.md §10). Callers ({@code BatchService}) never change.
 */
public interface TaskExecutor extends Managed {

    /** Submits one unit of work. May apply backpressure; never spawns unbounded threads. */
    void submit(Runnable task);

    int activeCount();

    int queueSize();
}
