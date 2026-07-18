package com.example.promptbatch.service;

import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.PromptResult;

/**
 * Folds one prompt's result into a batch and drives completion side effects. An interface so
 * completion behavior (emit a webhook, push an event) can be added later by decorating this
 * seam - never by editing {@code PromptTask} (LLD.md §5.7).
 */
public interface Aggregator {

    void record(Batch batch, PromptResult result);
}
