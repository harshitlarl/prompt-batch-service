package com.example.promptbatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.model.PromptResult;
import com.example.promptbatch.store.ResultStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class CompletionAggregatorTest {

    @Test
    void aggregationCountsAndStatusAreCorrectForMixedOutcomes() {
        Batch batch = new Batch("b1", 4);
        batch.status(BatchStatus.PROCESSING);
        ResultStore store = mock(ResultStore.class);
        Aggregator aggregator = new CompletionAggregator(store);

        aggregator.record(batch, PromptResult.success("p0", "ok", 1));
        aggregator.record(batch, PromptResult.failure("p1", "err", 0));
        aggregator.record(batch, PromptResult.success("p2", "ok", 1));
        aggregator.record(batch, PromptResult.failure("p3", "err", 0));

        assertThat(batch.succeeded()).isEqualTo(2);
        assertThat(batch.failed()).isEqualTo(2);
        assertThat(batch.status()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void finalizesResultStoreExactlyOnceOnCompletion() {
        Batch batch = new Batch("b1", 2);
        batch.status(BatchStatus.PROCESSING);
        ResultStore store = mock(ResultStore.class);
        Aggregator aggregator = new CompletionAggregator(store);

        aggregator.record(batch, PromptResult.success("p0", "ok", 1));
        verify(store, times(0)).finalizeBatch(ArgumentMatchers.any());

        aggregator.record(batch, PromptResult.success("p1", "ok", 1));
        verify(store, times(1)).finalizeBatch(batch);
    }

    @Test
    void writesEveryResultToTheStoreIncrementally() {
        Batch batch = new Batch("b1", 2);
        batch.status(BatchStatus.PROCESSING);
        ResultStore store = mock(ResultStore.class);
        Aggregator aggregator = new CompletionAggregator(store);

        PromptResult r0 = PromptResult.success("p0", "ok", 1);
        PromptResult r1 = PromptResult.success("p1", "ok", 1);
        aggregator.record(batch, r0);
        aggregator.record(batch, r1);

        verify(store, times(1)).write("b1", r0);
        verify(store, times(1)).write("b1", r1);
    }

    @Test
    void concurrentUpdatesFromManyWorkersAreConsistentAndFinalizeOnce() throws Exception {
        int total = 1_000;
        Batch batch = new Batch("b1", total);
        batch.status(BatchStatus.PROCESSING);
        ResultStore store = mock(ResultStore.class);
        Aggregator aggregator = new CompletionAggregator(store);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch done = new CountDownLatch(total);
        for (int i = 0; i < total; i++) {
            final int id = i;
            pool.submit(() -> {
                aggregator.record(batch, PromptResult.success("p" + id, "ok", 1));
                done.countDown();
            });
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(batch.succeeded()).isEqualTo(total);
        assertThat(batch.status()).isEqualTo(BatchStatus.COMPLETED);
        verify(store, times(1)).finalizeBatch(batch);
    }
}
