package com.example.promptbatch.worker;

import com.example.promptbatch.client.InferenceClient;
import com.example.promptbatch.client.InferenceResponse;
import com.example.promptbatch.client.RateLimitedException;
import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.Prompt;
import com.example.promptbatch.model.PromptResult;
import com.example.promptbatch.service.Aggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The per-prompt unit of work run inside the bounded pool: call the (retrying) inference
 * client, build a {@link PromptResult}, and report it to the {@link Aggregator}.
 *
 * <p><b>Never throws out of {@link #run()}.</b> Any failure - retries-exhausted or otherwise
 * unexpected - becomes a {@code FAILED} result so one bad prompt can never kill a worker
 * thread or fail the whole batch (SOLUTIONING.md §4.10, Principle 7 in LLD.md §2).
 */
public class PromptTask implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(PromptTask.class);

    private final Batch batch;
    private final Prompt prompt;
    private final InferenceClient client;
    private final Aggregator aggregator;

    public PromptTask(Batch batch, Prompt prompt, InferenceClient client, Aggregator aggregator) {
        this.batch = batch;
        this.prompt = prompt;
        this.client = client;
        this.aggregator = aggregator;
    }

    @Override
    public void run() {
        MDC.put("batchId", batch.id());
        MDC.put("promptId", prompt.id());
        try {
            PromptResult result;
            try {
                InferenceResponse response = client.infer(prompt);
                result = PromptResult.success(prompt.id(), response.output(), 1);
            } catch (RateLimitedException e) {
                result = PromptResult.failure(prompt.id(), e.getMessage(), 0);
            } catch (RuntimeException e) {
                LOG.error("Unexpected error processing prompt {}", prompt.id(), e);
                result = PromptResult.failure(prompt.id(), "error: " + e.getMessage(), 0);
            }
            aggregator.record(batch, result);
        } finally {
            MDC.remove("batchId");
            MDC.remove("promptId");
        }
    }
}
