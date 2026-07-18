package com.example.promptbatch.worker;

import com.example.promptbatch.client.InferenceClient;
import com.example.promptbatch.config.RecoveryConfig;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.model.PromptRecoveryCandidate;
import com.example.promptbatch.model.PromptResult;
import com.example.promptbatch.repository.BatchRepository;
import com.example.promptbatch.service.Aggregator;
import io.dropwizard.lifecycle.Managed;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Background fault-tolerance sweep, independent of any instance restarting: periodically asks
 * the {@link BatchRepository} for prompts that belong to a not-yet-finished batch, have no
 * result, and haven't been (re-)attempted recently - i.e. work whose worker/container plausibly
 * crashed, was killed, or hung mid-task - and resubmits it to a small, <b>dedicated</b> retry
 * pool so it can never compete with (or get starved by) the primary pool handling fresh
 * requests.
 *
 * <p>This is the continuous counterpart to {@code PromptBatchApplication#recoverInFlightBatches},
 * which only runs once at startup. Together: a crashed container's unfinished work is picked up
 * either by the next instance to boot (startup pass) <b>or</b>, without waiting for any restart
 * at all, by any live instance's periodic sweep (this class) - whichever happens first.
 *
 * <p>Each candidate that has already exhausted {@code maxAttempts} is <b>not</b> retried again;
 * instead a terminal {@code FAILED} result is recorded so its batch can still complete rather
 * than staying stuck forever behind one permanently-broken prompt.
 */
public class StaleTaskRecoveryService implements Managed {

    private static final Logger LOG = LoggerFactory.getLogger(StaleTaskRecoveryService.class);

    private final BatchRepository repository;
    private final TaskExecutor retryExecutor;
    private final InferenceClient inferenceClient;
    private final Aggregator aggregator;
    private final RecoveryConfig config;

    private final AtomicReference<Instant> lastSweepAt = new AtomicReference<>();
    private final AtomicInteger lastRetriedCount = new AtomicInteger();
    private final AtomicInteger lastAbandonedCount = new AtomicInteger();

    private ScheduledExecutorService scheduler;

    public StaleTaskRecoveryService(
            BatchRepository repository,
            TaskExecutor retryExecutor,
            InferenceClient inferenceClient,
            Aggregator aggregator,
            RecoveryConfig config) {
        this.repository = repository;
        this.retryExecutor = retryExecutor;
        this.inferenceClient = inferenceClient;
        this.aggregator = aggregator;
        this.config = config;
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stale-task-recovery");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(
                this::sweepSafely, config.getIntervalSeconds(), config.getIntervalSeconds(), TimeUnit.SECONDS);
        LOG.info(
                "Stale task recovery started: every {}s, staleAfter={}s, maxAttempts={}",
                config.getIntervalSeconds(), config.getStaleAfterSeconds(), config.getMaxAttempts());
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** Never lets a bad sweep kill the schedule - one failed sweep just gets retried next tick. */
    private void sweepSafely() {
        try {
            sweep();
        } catch (RuntimeException e) {
            LOG.error("Stale task recovery sweep failed; will retry on next interval", e);
        }
    }

    private void sweep() {
        List<PromptRecoveryCandidate> candidates =
                repository.staleUnfinishedPrompts(Duration.ofSeconds(config.getStaleAfterSeconds()));
        lastSweepAt.set(Instant.now());
        int retried = 0;
        int abandoned = 0;
        for (PromptRecoveryCandidate candidate : candidates) {
            if (candidate.attemptCount() >= config.getMaxAttempts()) {
                if (abandon(candidate)) {
                    abandoned++;
                }
            } else {
                if (retry(candidate)) {
                    retried++;
                }
            }
        }
        lastRetriedCount.set(retried);
        lastAbandonedCount.set(abandoned);
        if (retried > 0 || abandoned > 0) {
            LOG.info(
                    "Stale task recovery sweep: {} candidate(s), {} resubmitted, {} abandoned (max attempts reached)",
                    candidates.size(), retried, abandoned);
        }
    }

    private boolean retry(PromptRecoveryCandidate candidate) {
        return repository.find(candidate.batchId())
                .filter(batch -> batch.status() != BatchStatus.COMPLETED && batch.status() != BatchStatus.FAILED)
                .map(batch -> {
                    MDC.put("batchId", batch.id());
                    MDC.put("promptId", candidate.prompt().id());
                    try {
                        LOG.warn(
                                "Resubmitting stuck prompt {} in batch {} (attempt {}/{}) - no result "
                                        + "within staleAfter window, likely lost to a crashed/hung worker",
                                candidate.prompt().id(), batch.id(), candidate.attemptCount() + 1, config.getMaxAttempts());
                        repository.recordAttempt(batch.id(), candidate.prompt().id());
                        retryExecutor.submit(new PromptTask(batch, candidate.prompt(), inferenceClient, aggregator));
                        return true;
                    } finally {
                        MDC.remove("batchId");
                        MDC.remove("promptId");
                    }
                })
                .orElse(false);
    }

    private boolean abandon(PromptRecoveryCandidate candidate) {
        return repository.find(candidate.batchId())
                .filter(batch -> batch.status() != BatchStatus.COMPLETED && batch.status() != BatchStatus.FAILED)
                .map(batch -> {
                    LOG.error(
                            "Giving up on prompt {} in batch {} after {} attempts with no result; recording "
                                    + "terminal failure so the batch can still complete",
                            candidate.prompt().id(), batch.id(), candidate.attemptCount());
                    PromptResult result = PromptResult.failure(
                            candidate.prompt().id(),
                            "exceeded max recovery attempts (" + config.getMaxAttempts() + ")",
                            candidate.attemptCount());
                    aggregator.record(batch, result);
                    return true;
                })
                .orElse(false);
    }

    /** Backs a health check surfacing whether the sweep is actually running/making progress. */
    public Instant lastSweepAt() {
        return lastSweepAt.get();
    }

    public int lastRetriedCount() {
        return lastRetriedCount.get();
    }

    public int lastAbandonedCount() {
        return lastAbandonedCount.get();
    }
}
