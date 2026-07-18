package com.example.promptbatch.service;

import com.example.promptbatch.client.InferenceClient;
import com.example.promptbatch.exception.BadInputException;
import com.example.promptbatch.exception.BatchNotFoundException;
import com.example.promptbatch.ingest.PromptSource;
import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.model.Prompt;
import com.example.promptbatch.repository.BatchRepository;
import com.example.promptbatch.worker.PromptTask;
import com.example.promptbatch.worker.TaskExecutor;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Orchestrator: creates the batch record, submits one {@link PromptTask} per prompt to the
 * bounded {@link TaskExecutor}, and returns - <b>no inference happens on the calling thread</b>
 * (SOLUTIONING.md F2, LLD.md §5.7). This is what lets the REST resource answer {@code 202}
 * immediately.
 */
public class BatchService {

    private static final Logger LOG = LoggerFactory.getLogger(BatchService.class);

    private final BatchRepository repository;
    private final TaskExecutor taskExecutor;
    private final InferenceClient inferenceClient;
    private final Aggregator aggregator;

    public BatchService(
            BatchRepository repository,
            TaskExecutor taskExecutor,
            InferenceClient inferenceClient,
            Aggregator aggregator) {
        this.repository = repository;
        this.taskExecutor = taskExecutor;
        this.inferenceClient = inferenceClient;
        this.aggregator = aggregator;
    }

    /** Submits a batch given already-parsed prompt texts (the JSON API path). */
    public Batch submit(List<String> promptTexts) {
        if (promptTexts == null || promptTexts.isEmpty()) {
            throw new BadInputException("prompts must not be empty");
        }
        String batchId = newBatchId();
        int i = 0;
        List<Prompt> prompts = new java.util.ArrayList<>(promptTexts.size());
        for (String text : promptTexts) {
            prompts.add(new Prompt(batchId + "-p" + (i++), text));
        }
        return enqueue(batchId, prompts);
    }

    /** Submits a batch parsed from a raw upload via the selected {@link PromptSource}. */
    public Batch submitFromSource(PromptSource source, InputStream raw) {
        String batchId = newBatchId();
        List<Prompt> prompts = source.parse(batchId, raw);
        return enqueue(batchId, prompts);
    }

    public Batch get(String id) {
        return repository.find(id).orElseThrow(() -> new BatchNotFoundException(id));
    }

    /** All known batches, most recently submitted first (backs the batch-list UI/API). */
    public List<Batch> listAll() {
        return repository.listAll();
    }

    private Batch enqueue(String batchId, List<Prompt> prompts) {
        Batch batch = new Batch(batchId, prompts.size());
        batch.status(BatchStatus.PROCESSING);
        repository.save(batch);
        // No-op for stores that can't recover across a restart; for a shared/durable store
        // (Postgres) this is what lets a *different* container resume the batch if the one that
        // enqueued it dies mid-processing (see PromptBatchApplication's startup recovery pass).
        repository.savePrompts(batchId, prompts);

        MDC.put("batchId", batchId);
        try {
            LOG.info("Batch {} received with {} prompts", batchId, prompts.size());
            for (Prompt prompt : prompts) {
                // No-op for stores that don't track attempts; for the durable store this marks
                // the prompt as claimed *now*, so the background stale-task sweep won't mistake
                // a prompt that's simply still queued/in-flight normally for one lost to a
                // crashed worker until a full staleAfter window has passed with no result.
                repository.recordAttempt(batchId, prompt.id());
                taskExecutor.submit(new PromptTask(batch, prompt, inferenceClient, aggregator));
            }
            LOG.info("Batch {} enqueued to worker pool", batchId);
        } finally {
            MDC.remove("batchId");
        }
        return batch;
    }

    private String newBatchId() {
        return "b-" + UUID.randomUUID();
    }
}
