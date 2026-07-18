package com.example.promptbatch;

import com.example.promptbatch.config.MockEndpointConfig;
import com.example.promptbatch.constants.AppConstants;
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

    @Valid
    @NotNull
    @JsonProperty("retry")
    private RetryConfig retry = new RetryConfig();

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
