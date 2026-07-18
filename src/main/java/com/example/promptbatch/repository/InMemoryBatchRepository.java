package com.example.promptbatch.repository;

import com.example.promptbatch.model.Batch;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1 implementation of {@link BatchRepository}: a {@link ConcurrentHashMap} keyed by batch id.
 * No external locking needed; safe for many workers/readers concurrently.
 *
 * <p><b>Known limitation:</b> state is lost on process restart. Addressed by a durable
 * repository (e.g. Postgres) in later migration phases (SOLUTIONING.md §9, Phase P1).
 */
public class InMemoryBatchRepository implements BatchRepository {

    private final Map<String, Batch> batches = new ConcurrentHashMap<>();

    @Override
    public void save(Batch batch) {
        batches.put(batch.id(), batch);
    }

    @Override
    public Optional<Batch> find(String id) {
        return Optional.ofNullable(batches.get(id));
    }

    @Override
    public int count() {
        return batches.size();
    }

    @Override
    public List<Batch> listAll() {
        return batches.values().stream()
                .sorted(Comparator.comparing(Batch::createdAt).reversed())
                .toList();
    }
}
