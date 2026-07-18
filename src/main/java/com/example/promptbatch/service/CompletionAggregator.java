package com.example.promptbatch.service;

import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.PromptResult;
import com.example.promptbatch.store.ResultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * v1 {@link Aggregator}: all atomicity/counting lives in {@code Batch} itself, so concurrent
 * {@link #record} calls from N workers are always correct (LLD.md §6). This class is a thin,
 * stateless coordinator that streams each result to the {@link ResultStore} and finalizes the
 * store exactly once, on whichever thread's result completes the batch.
 */
public class CompletionAggregator implements Aggregator {

    private static final Logger LOG = LoggerFactory.getLogger(CompletionAggregator.class);

    private final ResultStore resultStore;

    public CompletionAggregator(ResultStore resultStore) {
        this.resultStore = resultStore;
    }

    @Override
    public void record(Batch batch, PromptResult result) {
        resultStore.write(batch.id(), result);
        boolean justCompleted = batch.recordResult(result);
        if (justCompleted) {
            MDC.put("batchId", batch.id());
            try {
                LOG.info(
                        "Batch {} COMPLETED: {} succeeded, {} failed of {}",
                        batch.id(), batch.succeeded(), batch.failed(), batch.total());
                resultStore.finalizeBatch(batch);
            } finally {
                MDC.remove("batchId");
            }
        }
    }
}
