package com.example.promptbatch.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BatchTest {

    @Test
    void completedEqualsSucceededPlusFailed() {
        Batch batch = new Batch("b1", 3);
        batch.status(BatchStatus.PROCESSING);

        batch.recordResult(PromptResult.success("p0", "ok", 1));
        batch.recordResult(PromptResult.failure("p1", "boom", 0));

        assertThat(batch.completed()).isEqualTo(batch.succeeded() + batch.failed());
        assertThat(batch.completed()).isEqualTo(2);
        assertThat(batch.status()).isEqualTo(BatchStatus.PROCESSING);
    }

    @Test
    void transitionsToCompletedWhenAllResultsIn() {
        Batch batch = new Batch("b1", 2);
        batch.status(BatchStatus.PROCESSING);

        batch.recordResult(PromptResult.success("p0", "ok", 1));
        assertThat(batch.status()).isEqualTo(BatchStatus.PROCESSING);

        batch.recordResult(PromptResult.success("p1", "ok", 1));
        assertThat(batch.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(batch.finishedAt()).isNotNull();
    }

    @Test
    void percentCompleteReflectsProgress() {
        Batch batch = new Batch("b1", 4);
        batch.recordResult(PromptResult.success("p0", "ok", 1));
        assertThat(batch.percent()).isEqualTo(25.0);
    }

    @Test
    void recordResultReturnsTrueExactlyOnceEvenUnderConcurrentCompletion() throws Exception {
        int total = 500;
        Batch batch = new Batch("b1", total);
        batch.status(BatchStatus.PROCESSING);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger completionSignals = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(total);

        for (int i = 0; i < total; i++) {
            final int id = i;
            pool.submit(() -> {
                boolean justCompleted = batch.recordResult(PromptResult.success("p" + id, "ok", 1));
                if (justCompleted) {
                    completionSignals.incrementAndGet();
                }
                done.countDown();
            });
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(batch.succeeded()).isEqualTo(total);
        assertThat(batch.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(completionSignals.get()).isEqualTo(1);
    }

    @Test
    void resultsAreKeyedByPromptId() {
        Batch batch = new Batch("b1", 1);
        batch.recordResult(PromptResult.success("p0", "ok", 1));
        assertThat(batch.results()).extracting(PromptResult::promptId).containsExactly("p0");
        assertThat(List.copyOf(batch.results())).hasSize(1);
    }
}
