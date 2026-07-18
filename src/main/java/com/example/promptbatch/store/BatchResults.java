package com.example.promptbatch.store;

import com.example.promptbatch.model.PromptResult;
import java.util.Collection;

/** Read-model returned by {@link ResultStore#read}: the aggregated view of a finished batch. */
public record BatchResults(String batchId, String status, Collection<PromptResult> results) {
}
