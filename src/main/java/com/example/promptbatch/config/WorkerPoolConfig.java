package com.example.promptbatch.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/** Sizing knobs for the bounded worker pool (N1/N2 in SOLUTIONING.md §2). */
@Getter
@Setter
public class WorkerPoolConfig {

    @Min(1)
    @JsonProperty
    private int size = 8;

    @Min(1)
    @JsonProperty
    private int queueCapacity = 10_000;
}
