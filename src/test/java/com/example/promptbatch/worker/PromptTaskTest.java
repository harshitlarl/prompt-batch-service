package com.example.promptbatch.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.promptbatch.client.InferenceClient;
import com.example.promptbatch.client.InferenceResponse;
import com.example.promptbatch.client.RateLimitedException;
import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.model.Prompt;
import com.example.promptbatch.service.CompletionAggregator;
import com.example.promptbatch.store.InMemoryResultStore;
import org.junit.jupiter.api.Test;

class PromptTaskTest {

    @Test
    void recordsSuccessAndCompletesBatch() {
        Batch batch = new Batch("b1", 1);
        batch.status(BatchStatus.PROCESSING);
        InferenceClient client = p -> new InferenceResponse("ok:" + p.text());

        new PromptTask(batch, new Prompt("b1-p0", "hi"), client, new CompletionAggregator(new InMemoryResultStore())).run();

        assertThat(batch.succeeded()).isEqualTo(1);
        assertThat(batch.failed()).isZero();
        assertThat(batch.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(batch.results()).extracting(r -> r.output()).containsExactly("ok:hi");
    }

    @Test
    void recordsFailureWhenClientThrowsRateLimitedException() {
        Batch batch = new Batch("b1", 1);
        batch.status(BatchStatus.PROCESSING);
        InferenceClient client = p -> {
            throw new RateLimitedException("retries exhausted");
        };

        new PromptTask(batch, new Prompt("b1-p0", "hi"), client, new CompletionAggregator(new InMemoryResultStore())).run();

        assertThat(batch.failed()).isEqualTo(1);
        assertThat(batch.succeeded()).isZero();
        assertThat(batch.status()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void neverThrowsOutOfRunEvenOnUnexpectedException() {
        Batch batch = new Batch("b1", 1);
        batch.status(BatchStatus.PROCESSING);
        InferenceClient client = p -> {
            throw new IllegalStateException("boom");
        };

        PromptTask task = new PromptTask(batch, new Prompt("b1-p0", "hi"), client,
                new CompletionAggregator(new InMemoryResultStore()));

        task.run(); // must not propagate

        assertThat(batch.failed()).isEqualTo(1);
        assertThat(batch.results().iterator().next().failureReason()).contains("boom");
    }

    @Test
    void oneFailingPromptDoesNotPreventSiblingsFromSucceeding() {
        Batch batch = new Batch("b1", 2);
        batch.status(BatchStatus.PROCESSING);
        var aggregator = new CompletionAggregator(new InMemoryResultStore());

        new PromptTask(batch, new Prompt("b1-p0", "hi"),
                p -> { throw new RateLimitedException("exhausted"); }, aggregator).run();
        new PromptTask(batch, new Prompt("b1-p1", "hi"),
                p -> new InferenceResponse("ok"), aggregator).run();

        assertThat(batch.failed()).isEqualTo(1);
        assertThat(batch.succeeded()).isEqualTo(1);
        assertThat(batch.status()).isEqualTo(BatchStatus.COMPLETED);
    }
}
