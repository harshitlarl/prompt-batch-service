package com.example.promptbatch.config;

import com.example.promptbatch.constants.AppConstants;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

/**
 * Selects and configures the {@code BatchRepository}/{@code ResultStore} implementation (see
 * repository/BatchRepository.java and store/ResultStore.java).
 */
@Getter
@Setter
public class StoreConfig {

    /**
     * {@code sqlite} (default: durable, crash-safe, queryable via a single file - see
     * store/sqlite/SqliteBatchStore.java), {@code json-file} (one JSON file per finalized
     * batch), or {@code in-memory} (v1 demo only; all state lost on restart).
     */
    @NotEmpty
    @JsonProperty
    private String type = AppConstants.STORE_TYPE_SQLITE;

    /** Directory used by the {@code json-file} store type. */
    @NotEmpty
    @JsonProperty
    private String directory = "./data/batches";

    /** SQLite database file used by the {@code sqlite} store type. */
    @NotEmpty
    @JsonProperty
    private String path = "./data/batches.db";
}
