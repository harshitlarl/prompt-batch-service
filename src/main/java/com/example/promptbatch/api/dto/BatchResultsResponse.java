package com.example.promptbatch.api.dto;

import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.PromptResult;
import java.util.Collection;

public record BatchResultsResponse(String batchId, String status, Collection<PromptResult> results) {

    public static BatchResultsResponse from(Batch batch) {
        return new BatchResultsResponse(batch.id(), batch.status().name(), batch.results());
    }
}
