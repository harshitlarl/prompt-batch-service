package com.example.promptbatch.config;

import com.example.promptbatch.constants.AppConstants;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
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
     * {@code postgres} (default: shared, durable store so multiple app instances/containers can
     * see the same batch state - see store/postgres), {@code json-file} (one JSON file per
     * finalized batch), or {@code in-memory} (v1 demo only; all state lost on restart).
     */
    @NotEmpty
    @JsonProperty
    private String type = AppConstants.STORE_TYPE_POSTGRES;

    /** Directory used by the {@code json-file} store type. */
    @NotEmpty
    @JsonProperty
    private String directory = "./data/batches";

    /**
     * Connection string used by the {@code postgres} store type, in either
     * {@code postgresql://user:password@host:port/dbname?sslmode=require} form (what
     * DigitalOcean hands you for a Managed Database, and what App Platform injects as
     * {@code ${db.DATABASE_URL}}) or a plain {@code jdbc:postgresql://...} URL.
     */
    @JsonProperty
    private String databaseUrl = "";

    /** HikariCP pool size per app instance for the {@code postgres} store type. */
    @Min(1)
    @JsonProperty
    private int maxPoolSize = 10;
}
