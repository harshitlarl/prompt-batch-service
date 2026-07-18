package com.example.promptbatch.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/** Retry/backoff knobs consulted by {@code ExponentialJitterBackoff} and the retrying client. */
@Getter
@Setter
public class RetryConfig {

    @Min(0)
    @JsonProperty
    private int maxRetries = 4;

    @Min(1)
    @JsonProperty
    private long baseDelayMs = 500;

    @Min(1)
    @JsonProperty
    private long maxDelayMs = 5_000;

    @JsonProperty
    private boolean jitter = true;
}
