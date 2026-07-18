package com.example.promptbatch.store;

import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.PromptResult;
import java.util.Optional;

/**
 * Seam S5 (LLD.md §4): persists per-prompt and aggregated batch results.
 *
 * <p>Deliberately split into an incremental {@link #write} and a terminal {@link #finalize}
 * so a streaming/object-store implementation (e.g. S3) can flush per-prompt and seal a final
 * artifact without changing the {@code Aggregator}.
 */
public interface ResultStore {

    /** Called once per finished prompt (stream-friendly). */
    void write(String batchId, PromptResult result);

    /** Called exactly once, when the batch transitions to COMPLETED. */
    void finalizeBatch(Batch batch);

    Optional<BatchResults> read(String batchId);
}
