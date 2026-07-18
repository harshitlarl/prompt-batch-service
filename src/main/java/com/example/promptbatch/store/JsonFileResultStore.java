package com.example.promptbatch.store;

import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.PromptResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.example.promptbatch.model.PromptOutcome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists one JSON file per finalized batch under a configured directory - the "final JSON
 * output" from SOLUTIONING.md §4.5. Read falls back to disk if the batch isn't in memory
 * (e.g. after a process restart, for whatever survived).
 */
public class JsonFileResultStore implements ResultStore {

    private static final Logger LOG = LoggerFactory.getLogger(JsonFileResultStore.class);

    private final Path directory;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public JsonFileResultStore(String directory) {
        this.directory = Path.of(directory);
        try {
            Files.createDirectories(this.directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create result store directory: " + directory, e);
        }
    }

    @Override
    public void write(String batchId, PromptResult result) {
        // Per-prompt streaming write is not needed for v1's file-per-batch layout;
        // finalizeBatch() writes the complete aggregated artifact once.
    }

    @Override
    public void finalizeBatch(Batch batch) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("batchId", batch.id());
        document.put("status", batch.status().name());
        document.put("total", batch.total());
        document.put("succeeded", batch.succeeded());
        document.put("failed", batch.failed());
        document.put("startedAt", batch.createdAt().toString());
        document.put("finishedAt", batch.finishedAt() == null ? null : batch.finishedAt().toString());
        document.put("results", batch.results());

        Path file = directory.resolve(batch.id() + ".json");
        try {
            mapper.writeValue(file.toFile(), document);
            LOG.info("Wrote final results for batch {} to {}", batch.id(), file);
        } catch (IOException e) {
            LOG.error("Failed to write results for batch {} to {}", batch.id(), file, e);
        }
    }

    @Override
    public Optional<BatchResults> read(String batchId) {
        Path file = directory.resolve(batchId + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> document = mapper.readValue(file.toFile(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawResults =
                    (List<Map<String, Object>>) document.getOrDefault("results", List.of());
            List<PromptResult> results = new ArrayList<>();
            for (Map<String, Object> raw : rawResults) {
                results.add(new PromptResult(
                        (String) raw.get("promptId"),
                        PromptOutcome.valueOf((String) raw.get("outcome")),
                        (String) raw.get("output"),
                        (String) raw.get("failureReason"),
                        raw.get("attempts") == null ? 0 : ((Number) raw.get("attempts")).intValue()));
            }
            return Optional.of(new BatchResults(batchId, (String) document.get("status"), results));
        } catch (IOException e) {
            LOG.error("Failed to read results for batch {} from {}", batchId, file, e);
            return Optional.empty();
        }
    }
}
