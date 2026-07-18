package com.example.promptbatch.repository;

import com.example.promptbatch.model.Batch;
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
}
