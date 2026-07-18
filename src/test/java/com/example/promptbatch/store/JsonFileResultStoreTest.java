package com.example.promptbatch.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.model.PromptResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonFileResultStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void finalizeWritesAJsonFilePerBatch() throws Exception {
        ResultStore store = new JsonFileResultStore(tempDir.toString());
        Batch batch = new Batch("b1", 2);
        batch.status(BatchStatus.PROCESSING);
        batch.recordResult(PromptResult.success("p0", "ok", 1));
        batch.recordResult(PromptResult.failure("p1", "boom", 4));

        store.finalizeBatch(batch);

        Path file = tempDir.resolve("b1.json");
        assertThat(Files.exists(file)).isTrue();
        String content = Files.readString(file);
        assertThat(content).contains("\"batchId\"", "\"succeeded\" : 1", "\"failed\" : 1");
    }

    @Test
    void readReturnsResultsWrittenByFinalize() {
        ResultStore store = new JsonFileResultStore(tempDir.toString());
        Batch batch = new Batch("b2", 1);
        batch.status(BatchStatus.PROCESSING);
        batch.recordResult(PromptResult.success("p0", "output-value", 2));
        store.finalizeBatch(batch);

        var results = store.read("b2");

        assertThat(results).isPresent();
        assertThat(results.get().status()).isEqualTo("COMPLETED");
        assertThat(results.get().results()).hasSize(1);
        PromptResult r = results.get().results().iterator().next();
        assertThat(r.promptId()).isEqualTo("p0");
        assertThat(r.output()).isEqualTo("output-value");
        assertThat(r.attempts()).isEqualTo(2);
    }

    @Test
    void readReturnsEmptyForUnknownBatch() {
        ResultStore store = new JsonFileResultStore(tempDir.toString());
        assertThat(store.read("does-not-exist")).isEmpty();
    }
}
