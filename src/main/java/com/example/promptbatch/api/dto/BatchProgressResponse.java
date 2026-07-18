package com.example.promptbatch.api.dto;

import com.example.promptbatch.model.Batch;
import java.time.Instant;

public record BatchProgressResponse(
        String batchId,
        String status,
        int total,
        int completed,
        int succeeded,
        int failed,
        double percentComplete,
        Instant createdAt,
        Instant finishedAt) {

    public static BatchProgressResponse from(Batch batch) {
        return new BatchProgressResponse(
                batch.id(),
                batch.status().name(),
                batch.total(),
                batch.completed(),
                batch.succeeded(),
                batch.failed(),
                batch.percent(),
                batch.createdAt(),
                batch.finishedAt());
    }
}
