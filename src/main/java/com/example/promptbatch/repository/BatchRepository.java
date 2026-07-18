package com.example.promptbatch.repository;

import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.Prompt;
import com.example.promptbatch.model.PromptRecoveryCandidate;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Seam S4 (LLD.md §4): owns the live state of every batch. v1 keeps it in memory; at scale
 * this swaps for Postgres + Redis-backed counters (LLD.md §10) without touching
 * {@code BatchService}/{@code Aggregator}, which only ever hold this interface.
 */
public interface BatchRepository {

    void save(Batch batch);

    Optional<Batch> find(String id);

    /** Number of batches currently held (used to back a metrics {@code Gauge}). */
    int count();

    /** All known batches, most recently created first (backs the "list all batches" UI/API). */
    List<Batch> listAll();

    /**
     * Durably records the full set of prompts submitted for a batch, <b>before</b> any of them
     * are handed to the worker pool. Only durable, shared repositories (e.g. the Postgres store)
     * need this to support crash recovery; in-memory/single-process stores can no-op since there
     * is nothing to recover across a process restart anyway.
     */
    default void savePrompts(String batchId, List<Prompt> prompts) {}

    /**
     * Prompts previously recorded by {@link #savePrompts} for {@code batchId} that have
     * <b>not</b> yet produced a result. Used on startup to re-enqueue work left unfinished by a
     * crashed/killed container so a batch isn't stuck forever just because the instance that
     * was processing it died mid-batch. Stores that don't support recovery return an empty list.
     */
    default List<Prompt> pendingPrompts(String batchId) {
        return List.of();
    }

    /**
     * Records that a worker (initial submit, startup recovery, or the background retry sweep)
     * just picked up {@code promptId} for execution: bumps its attempt count and refreshes
     * "last attempted at" so {@link #staleUnfinishedPrompts} won't consider it stale again
     * until another full {@code staleAfter} window has passed with still no result. No-op for
     * stores that don't support recovery.
     */
    default void recordAttempt(String batchId, String promptId) {}

    /**
     * Prompts across <b>all</b> non-terminal batches that have no result yet and haven't been
     * attempted within {@code staleAfter} (or have never been attempted at all). This is what
     * lets a background sweeper - not just a one-time startup scan - notice that the
     * container/thread that previously claimed a prompt died or hung without ever producing a
     * result, independent of whether any instance has restarted. Returns each candidate's
     * current attempt count so the caller can decide whether to retry again or give up.
     * Stores that don't support recovery return an empty list.
     */
    default List<PromptRecoveryCandidate> staleUnfinishedPrompts(Duration staleAfter) {
        return List.of();
    }
}
