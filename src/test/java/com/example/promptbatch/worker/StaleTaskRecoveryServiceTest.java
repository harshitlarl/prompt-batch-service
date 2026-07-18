package com.example.promptbatch.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.promptbatch.client.InferenceClient;
import com.example.promptbatch.client.InferenceResponse;
import com.example.promptbatch.config.RecoveryConfig;
import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.model.Prompt;
import com.example.promptbatch.model.PromptRecoveryCandidate;
import com.example.promptbatch.repository.BatchRepository;
import com.example.promptbatch.repository.InMemoryBatchRepository;
import com.example.promptbatch.service.Aggregator;
import com.example.promptbatch.service.CompletionAggregator;
import com.example.promptbatch.store.InMemoryResultStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the two outcomes of a sweep: prompts under {@code maxAttempts} get resubmitted to
 * the dedicated retry pool (never the caller's/primary pool), and prompts that have already
 * exhausted {@code maxAttempts} are abandoned with a terminal failure so their batch can still
 * complete, instead of retrying forever.
 */
class StaleTaskRecoveryServiceTest {

    /** In-memory {@link BatchRepository} augmented with the recovery-only methods for tests. */
    static class FakeRecoveryRepository extends InMemoryBatchRepository {
        final List<PromptRecoveryCandidate> candidates = new CopyOnWriteArrayList<>();
        final AtomicInteger attemptsRecorded = new AtomicInteger();

        @Override
        public List<PromptRecoveryCandidate> staleUnfinishedPrompts(Duration staleAfter) {
            return List.copyOf(candidates);
        }

        @Override
        public void recordAttempt(String batchId, String promptId) {
            attemptsRecorded.incrementAndGet();
        }
    }

    private StaleTaskRecoveryService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    void resubmitsCandidateUnderMaxAttemptsToTheRetryPoolOnly() throws Exception {
        FakeRecoveryRepository repository = new FakeRecoveryRepository();
        Batch batch = new Batch("b-1", 2);
        batch.status(BatchStatus.PROCESSING);
        repository.save(batch);
        repository.candidates.add(new PromptRecoveryCandidate("b-1", new Prompt("b-1-p0", "hi"), 1));

        RecordingTaskExecutor retryExecutor = new RecordingTaskExecutor();
        InferenceClient client = p -> new InferenceResponse("ok:" + p.text());
        Aggregator aggregator = new CompletionAggregator(new InMemoryResultStore());
        RecoveryConfig config = new RecoveryConfig();
        config.setIntervalSeconds(1);
        config.setStaleAfterSeconds(1);
        config.setMaxAttempts(5);

        service = new StaleTaskRecoveryService(repository, retryExecutor, client, aggregator, config);
        service.start();

        awaitTrue(() -> retryExecutor.submittedCount.get() >= 1);

        assertThat(retryExecutor.submittedCount.get()).isEqualTo(1);
        assertThat(repository.attemptsRecorded.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void abandonsCandidateThatHasExhaustedMaxAttemptsInsteadOfRetryingForever() throws Exception {
        FakeRecoveryRepository repository = new FakeRecoveryRepository();
        Batch batch = new Batch("b-2", 1);
        batch.status(BatchStatus.PROCESSING);
        repository.save(batch);
        repository.candidates.add(new PromptRecoveryCandidate("b-2", new Prompt("b-2-p0", "hi"), 5));

        RecordingTaskExecutor retryExecutor = new RecordingTaskExecutor();
        InferenceClient client = p -> new InferenceResponse("ok:" + p.text());
        Aggregator aggregator = new CompletionAggregator(new InMemoryResultStore());
        RecoveryConfig config = new RecoveryConfig();
        config.setIntervalSeconds(1);
        config.setStaleAfterSeconds(1);
        config.setMaxAttempts(5);

        service = new StaleTaskRecoveryService(repository, retryExecutor, client, aggregator, config);
        service.start();

        awaitTrue(() -> repository.find("b-2").orElseThrow().status() == BatchStatus.COMPLETED);

        assertThat(retryExecutor.submittedCount.get()).isZero();
        assertThat(repository.find("b-2").orElseThrow().failed()).isEqualTo(1);
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Condition was not met within 5 seconds");
    }

    /** Records how many tasks were handed to this pool without ever running them concurrently elsewhere. */
    static class RecordingTaskExecutor implements TaskExecutor {
        final AtomicInteger submittedCount = new AtomicInteger();

        @Override
        public void submit(Runnable task) {
            submittedCount.incrementAndGet();
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
}
