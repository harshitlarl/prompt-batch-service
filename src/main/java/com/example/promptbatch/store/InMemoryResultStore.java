package com.example.promptbatch.store;

import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.PromptResult;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1 default {@link ResultStore}: results already live on the in-memory {@code Batch} (via
 * the repository), so this store just keeps a snapshot of finalized batches for the
 * {@code GET /batches/{id}/results} read path, keyed by batch id.
 */
public class InMemoryResultStore implements ResultStore {

    private final Map<String, BatchResults> finalized = new ConcurrentHashMap<>();

    @Override
    public void write(String batchId, PromptResult result) {
        // No-op: the Batch itself (held by BatchRepository) is the incremental source of truth
        // for v1. This method exists so the seam is exercised the same way a streaming
        // implementation (e.g. S3ResultStore) would use it.
    }

    @Override
    public void finalizeBatch(Batch batch) {
        finalized.put(batch.id(), new BatchResults(batch.id(), batch.status().name(), batch.results()));
    }

    @Override
    public Optional<BatchResults> read(String batchId) {
        return Optional.ofNullable(finalized.get(batchId));
    }
}
