package com.example.promptbatch.model;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live, shared, mutable state for one batch. Many worker threads call {@link #recordResult}
 * concurrently, so every field that participates in that call is either atomic or a
 * concurrent collection - no external locking is required or allowed.
 *
 * <p>Invariants (see LLD.md §5.1 and §6):
 * <ul>
 *   <li>{@code completed() == succeeded() + failed()} at all times.</li>
 *   <li>{@code status} moves only {@code QUEUED -> PROCESSING -> {COMPLETED|FAILED}}.</li>
 *   <li>{@code finishedAt} is set exactly once.</li>
 *   <li>The {@code PROCESSING -> COMPLETED} transition happens exactly once, on whichever
 *       thread's result pushes {@code completed} to {@code total} (guarded by CAS).</li>
 * </ul>
 */
public class Batch {

    private final String id;
    private final int total;
    private final Instant createdAt;
    private final AtomicReference<Instant> finishedAt = new AtomicReference<>();
    private final AtomicReference<BatchStatus> status = new AtomicReference<>(BatchStatus.QUEUED);

    private final AtomicInteger succeeded = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    private final Map<String, PromptResult> results = new ConcurrentHashMap<>();

    public Batch(String id, int total) {
        this(id, total, Instant.now());
    }

    private Batch(String id, int total, Instant createdAt) {
        this.id = id;
        this.total = total;
        this.createdAt = createdAt;
    }

    /**
     * Rehydrates a {@code Batch} from persisted state (e.g. {@code PostgresBatchStore#find}) after
     * a process restart. Unlike {@link #recordResult}, this does not re-derive the completion
     * transition - the persisted {@code status}/{@code finishedAt} are trusted as-is since they
     * were themselves written by a prior, already-correct {@code recordResult}/finalize call.
     */
    public static Batch restore(
            String id,
            int total,
            int succeeded,
            int failed,
            BatchStatus status,
            Instant createdAt,
            Instant finishedAt,
            Collection<PromptResult> results) {
        Batch batch = new Batch(id, total, createdAt);
        batch.succeeded.set(succeeded);
        batch.failed.set(failed);
        batch.status.set(status);
        if (finishedAt != null) {
            batch.finishedAt.set(finishedAt);
        }
        for (PromptResult result : results) {
            batch.results.put(result.promptId(), result);
        }
        return batch;
    }

    /**
     * Folds one prompt's result into the batch counters.
     *
     * @return {@code true} if this call is the one that transitioned the batch to
     *         {@code COMPLETED} (i.e. the caller is responsible for finalization side
     *         effects); {@code false} otherwise. Guaranteed to return {@code true} for
     *         exactly one call per batch.
     */
    public boolean recordResult(PromptResult result) {
        results.put(result.promptId(), result);
        if (result.isSuccess()) {
            succeeded.incrementAndGet();
        } else {
            failed.incrementAndGet();
        }
        if (completed() >= total) {
            finishedAt.compareAndSet(null, Instant.now());
            return status.compareAndSet(BatchStatus.PROCESSING, BatchStatus.COMPLETED);
        }
        return false;
    }

    public int completed() {
        return succeeded.get() + failed.get();
    }

    public double percent() {
        return total == 0 ? 100.0 : (completed() * 100.0) / total;
    }

    public String id() {
        return id;
    }

    public int total() {
        return total;
    }

    public int succeeded() {
        return succeeded.get();
    }

    public int failed() {
        return failed.get();
    }

    public BatchStatus status() {
        return status.get();
    }

    public void status(BatchStatus newStatus) {
        status.set(newStatus);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant finishedAt() {
        return finishedAt.get();
    }

    public Collection<PromptResult> results() {
        return results.values();
    }
}
