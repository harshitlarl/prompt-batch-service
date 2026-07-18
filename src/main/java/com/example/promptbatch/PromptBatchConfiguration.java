package com.example.promptbatch;

import com.example.promptbatch.config.MockEndpointConfig;
import com.example.promptbatch.constants.AppConstants;
import com.example.promptbatch.config.RecoveryConfig;
import com.example.promptbatch.config.RetryConfig;
import com.example.promptbatch.config.StoreConfig;
import com.example.promptbatch.config.WorkerPoolConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.federecio.dropwizard.swagger.SwaggerBundleConfiguration;
import io.dropwizard.core.Configuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Application configuration bound from config.yml.
 *
 * <p>Every concurrency/retry/mock-endpoint/store knob is externalized here so behavior is
 * tunable per environment without a rebuild (SOLUTIONING.md §4.9).
 */
@Getter
public class PromptBatchConfiguration extends Configuration {

    @Setter
    @JsonProperty
    private String serviceName = AppConstants.SERVICE_NAME;

    @Valid
    @NotNull
    @JsonProperty("workerPool")
    private WorkerPoolConfig workerPool = new WorkerPoolConfig();

    /**
     * A small, separate pool dedicated to re-running prompts picked up by
     * {@code StaleTaskRecoveryService} (and by the one-shot startup recovery pass). Kept apart
     * from {@link #workerPool} so a burst of retries for stuck/crashed work can never starve
     * normal incoming request processing - retries get their own bounded concurrency instead of
     * competing with fresh submissions for the same threads.
     */
    @Valid
    @NotNull
    @JsonProperty("retryWorkerPool")
    private WorkerPoolConfig retryWorkerPool = defaultRetryWorkerPool();

    @Valid
    @NotNull
    @JsonProperty("recovery")
    private RecoveryConfig recovery = new RecoveryConfig();

    @Valid
    @NotNull
    @JsonProperty("retry")
    private RetryConfig retry = new RetryConfig();

    private static WorkerPoolConfig defaultRetryWorkerPool() {
        WorkerPoolConfig config = new WorkerPoolConfig();
        config.setSize(2);
        config.setQueueCapacity(2_000);
        return config;
    }

    @Valid
    @NotNull
    @JsonProperty("mockEndpoint")
    private MockEndpointConfig mockEndpoint = new MockEndpointConfig();

    @Valid
    @NotNull
    @JsonProperty("store")
    private StoreConfig store = new StoreConfig();

    @Valid
    @NotNull
    @JsonProperty("swagger")
    private SwaggerBundleConfiguration swaggerBundleConfiguration = new SwaggerBundleConfiguration();
}
