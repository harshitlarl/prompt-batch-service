package com.example.promptbatch.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.model.PromptResult;
import com.example.promptbatch.repository.BatchRepository;
import com.example.promptbatch.store.ResultStore;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteBatchStoreTest {

    @TempDir
    Path tempDir;

    private SqliteBatchStore store;

    private SqliteBatchStore newStore() {
        store = new SqliteBatchStore(tempDir.resolve("batches.db").toString());
        return store;
    }

    @AfterEach
    void closeStore() throws Exception {
        if (store != null) {
            store.stop();
        }
    }

    @Test
    void savedBatchCanBeFoundById() {
        BatchRepository repository = newStore();
        Batch batch = new Batch("b1", 5);
        batch.status(BatchStatus.PROCESSING);

        repository.save(batch);

        assertThat(repository.find("b1")).isPresent();
        Batch reloaded = repository.find("b1").get();
        assertThat(reloaded.id()).isEqualTo("b1");
        assertThat(reloaded.total()).isEqualTo(5);
        assertThat(reloaded.status()).isEqualTo(BatchStatus.PROCESSING);
    }

    @Test
    void unknownIdReturnsEmpty() {
        BatchRepository repository = newStore();
        assertThat(repository.find("missing")).isEmpty();
    }

    @Test
    void writeIncrementsCountersAndPersistsResultsPerPrompt() {
        SqliteBatchStore sqlite = newStore();
        BatchRepository repository = sqlite;
        ResultStore resultStore = sqlite;

        Batch batch = new Batch("b2", 2);
        batch.status(BatchStatus.PROCESSING);
        repository.save(batch);

        resultStore.write("b2", PromptResult.success("p0", "ok", 1));
        resultStore.write("b2", PromptResult.failure("p1", "boom", 3));

        Batch reloaded = repository.find("b2").orElseThrow();
        assertThat(reloaded.succeeded()).isEqualTo(1);
        assertThat(reloaded.failed()).isEqualTo(1);
        assertThat(reloaded.results()).extracting(PromptResult::promptId)
                .containsExactlyInAnyOrder("p0", "p1");
    }

    @Test
    void finalizeBatchPersistsStatusAndFinishedAt() {
        SqliteBatchStore sqlite = newStore();
        BatchRepository repository = sqlite;
        ResultStore resultStore = sqlite;

        Batch batch = new Batch("b3", 1);
        batch.status(BatchStatus.PROCESSING);
        repository.save(batch);
        resultStore.write("b3", PromptResult.success("p0", "ok", 1));
        batch.recordResult(PromptResult.success("p0", "ok", 1));

        resultStore.finalizeBatch(batch);

        Batch reloaded = repository.find("b3").orElseThrow();
        assertThat(reloaded.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(reloaded.finishedAt()).isNotNull();

        var results = resultStore.read("b3");
        assertThat(results).isPresent();
        assertThat(results.get().status()).isEqualTo("COMPLETED");
        assertThat(results.get().results()).hasSize(1);
    }

    @Test
    void readReturnsEmptyForUnknownBatch() {
        ResultStore resultStore = newStore();
        assertThat(resultStore.read("does-not-exist")).isEmpty();
    }

    @Test
    void stateSurvivesReopeningTheSameFile() {
        Path dbFile = tempDir.resolve("crash-recovery.db");

        SqliteBatchStore first = new SqliteBatchStore(dbFile.toString());
        Batch batch = new Batch("b4", 2);
        batch.status(BatchStatus.PROCESSING);
        first.save(batch);
        first.write("b4", PromptResult.success("p0", "ok", 1));
        first.stop(); // simulate the process going away mid-batch

        SqliteBatchStore reopened = new SqliteBatchStore(dbFile.toString());
        try {
            Batch reloaded = reopened.find("b4").orElseThrow();
            assertThat(reloaded.succeeded()).isEqualTo(1);
            assertThat(reloaded.status()).isEqualTo(BatchStatus.PROCESSING);
            assertThat(reloaded.results()).extracting(PromptResult::promptId).containsExactly("p0");
        } finally {
            reopened.stop();
        }
    }
}
