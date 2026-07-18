package com.example.promptbatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.promptbatch.client.InferenceClient;
import com.example.promptbatch.client.InferenceResponse;
import com.example.promptbatch.client.RateLimitedException;
import com.example.promptbatch.exception.BadInputException;
import com.example.promptbatch.exception.BatchNotFoundException;
import com.example.promptbatch.ingest.LinePromptSource;
import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.repository.BatchRepository;
import com.example.promptbatch.repository.InMemoryBatchRepository;
import com.example.promptbatch.store.InMemoryResultStore;
import com.example.promptbatch.worker.TaskExecutor;
import io.dropwizard.lifecycle.Managed;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies the async boundary (F2): {@code submit} must return before processing completes,
 * and progress must reflect eventual completion.
 */
class BatchServiceTest {

    /** Runs submitted tasks synchronously, inline, on the calling thread - a test double. */
    static class InlineTaskExecutor implements TaskExecutor, Managed {
        final AtomicInteger submitted = new AtomicInteger();

        @Override
        public void submit(Runnable task) {
            submitted.incrementAndGet();
            task.run();
        }

        @Override
        public int activeCount() {
            return 0;
        }

        @Override
        public int queueSize() {
            return 0;
        }
    }

    /** Runs submitted tasks on a background thread so submit() can be observed returning first. */
    static class AsyncTaskExecutor implements TaskExecutor {
        private final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);

        @Override
        public void submit(Runnable task) {
            pool.submit(task);
        }

        @Override
        public int activeCount() {
            return 0;
        }

        @Override
        public int queueSize() {
            return 0;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
            pool.shutdownNow();
        }
    }

    @Test
    void submitRejectsEmptyPromptList() {
        BatchService service = new BatchService(
                new InMemoryBatchRepository(), new InlineTaskExecutor(),
                p -> new InferenceResponse("ok"), new CompletionAggregator(new InMemoryResultStore()));

        assertThatThrownBy(() -> service.submit(List.of())).isInstanceOf(BadInputException.class);
    }

    @Test
    void submitCreatesRunningBatchAndProcessesAllPromptsSynchronouslyWithInlineExecutor() {
        BatchRepository repository = new InMemoryBatchRepository();
        InferenceClient client = p -> new InferenceResponse("echo:" + p.text());
        BatchService service = new BatchService(
                repository, new InlineTaskExecutor(), client, new CompletionAggregator(new InMemoryResultStore()));

        Batch batch = service.submit(List.of("a", "b", "c"));

        assertThat(batch.total()).isEqualTo(3);
        assertThat(batch.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(batch.succeeded()).isEqualTo(3);
        assertThat(service.get(batch.id()).id()).isEqualTo(batch.id());
    }

    @Test
    void getThrowsBatchNotFoundForUnknownId() {
        BatchService service = new BatchService(
                new InMemoryBatchRepository(), new InlineTaskExecutor(),
                p -> new InferenceResponse("ok"), new CompletionAggregator(new InMemoryResultStore()));

        assertThatThrownBy(() -> service.get("nope")).isInstanceOf(BatchNotFoundException.class);
    }

    @Test
    void submitReturnsBeforeProcessingCompletesWhenWorkIsAsynchronous() throws InterruptedException {
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        AsyncTaskExecutor executor = new AsyncTaskExecutor();
        InferenceClient blockingClient = p -> {
            try {
                releaseWorkers.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new InferenceResponse("ok");
        };
        BatchService service = new BatchService(
                new InMemoryBatchRepository(), executor, blockingClient,
                new CompletionAggregator(new InMemoryResultStore()));

        Batch batch = service.submit(List.of("a", "b"));

        // submit() returned even though inference calls are still blocked - proves the HTTP
        // thread never waits on processing (F2 / area 2).
        assertThat(batch.status()).isEqualTo(BatchStatus.PROCESSING);
        assertThat(batch.completed()).isZero();

        releaseWorkers.countDown();
        Thread.sleep(200);
        assertThat(service.get(batch.id()).status()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void submitFromSourceParsesUploadAndEnqueuesPrompts() {
        BatchRepository repository = new InMemoryBatchRepository();
        BatchService service = new BatchService(
                repository, new InlineTaskExecutor(), p -> new InferenceResponse("ok"),
                new CompletionAggregator(new InMemoryResultStore()));

        var upload = new ByteArrayInputStream("first\nsecond\nthird\n".getBytes(StandardCharsets.UTF_8));
        Batch batch = service.submitFromSource(new LinePromptSource(), upload);

        assertThat(batch.total()).isEqualTo(3);
        assertThat(batch.status()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void promptIdsAreStableAndUniqueWithinABatch() {
        BatchRepository repository = new InMemoryBatchRepository();
        List<String> seenPromptIds = new ArrayList<>();
        InferenceClient client = p -> {
            seenPromptIds.add(p.id());
            return new InferenceResponse("ok");
        };
        BatchService service = new BatchService(
                repository, new InlineTaskExecutor(), client, new CompletionAggregator(new InMemoryResultStore()));

        Batch batch = service.submit(List.of("a", "b", "c"));

        assertThat(seenPromptIds).hasSize(3);
        assertThat(seenPromptIds).allMatch(id -> id.startsWith(batch.id()));
        assertThat(java.util.Set.copyOf(seenPromptIds)).hasSize(3); // all unique
    }
}
