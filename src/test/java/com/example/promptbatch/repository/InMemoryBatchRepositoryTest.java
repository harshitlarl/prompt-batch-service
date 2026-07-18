package com.example.promptbatch.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.promptbatch.model.Batch;
import org.junit.jupiter.api.Test;

class InMemoryBatchRepositoryTest {

    @Test
    void savedBatchCanBeFoundById() {
        BatchRepository repository = new InMemoryBatchRepository();
        Batch batch = new Batch("b1", 5);

        repository.save(batch);

        assertThat(repository.find("b1")).contains(batch);
    }

    @Test
    void unknownIdReturnsEmpty() {
        BatchRepository repository = new InMemoryBatchRepository();
        assertThat(repository.find("missing")).isEmpty();
    }
}
