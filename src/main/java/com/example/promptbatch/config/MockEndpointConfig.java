package com.example.promptbatch.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/** Behavior of the simulated inference endpoint used in v1. */
@Getter
@Setter
public class MockEndpointConfig {

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    @JsonProperty
    private double rateLimitProbability = 0.3;

    @Min(0)
    @JsonProperty
    private long baseLatencyMs = 150;
}
