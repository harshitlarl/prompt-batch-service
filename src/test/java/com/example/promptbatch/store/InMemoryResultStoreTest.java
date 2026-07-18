package com.example.promptbatch.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.model.PromptResult;
import org.junit.jupiter.api.Test;

class InMemoryResultStoreTest {

    @Test
    void readReturnsEmptyBeforeFinalize() {
        ResultStore store = new InMemoryResultStore();
        assertThat(store.read("b1")).isEmpty();
    }

    @Test
    void finalizeMakesResultsReadable() {
        ResultStore store = new InMemoryResultStore();
        Batch batch = new Batch("b1", 1);
        batch.status(BatchStatus.PROCESSING);
        batch.recordResult(PromptResult.success("p0", "ok", 1));

        store.finalizeBatch(batch);

        var results = store.read("b1");
        assertThat(results).isPresent();
        assertThat(results.get().status()).isEqualTo("COMPLETED");
        assertThat(results.get().results()).extracting(PromptResult::promptId).containsExactly("p0");
    }
}
