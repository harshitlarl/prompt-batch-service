package com.example.promptbatch.client;

import com.example.promptbatch.config.MockEndpointConfig;
import com.example.promptbatch.model.Prompt;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates a rate-limited external inference endpoint: adds latency, then randomly throws
 * {@link RateLimitedException} (as if the endpoint returned {@code 429}) with the configured
 * probability. Swappable for a real {@code HttpInferenceClient} without touching callers
 * (LLD.md §9.1).
 */
public class MockInferenceClient implements InferenceClient {

    private final MockEndpointConfig config;

    public MockInferenceClient(MockEndpointConfig config) {
        this.config = config;
    }

    @Override
    public InferenceResponse infer(Prompt prompt) {
        try {
            Thread.sleep(config.getBaseLatencyMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while simulating latency", e);
        }
        if (ThreadLocalRandom.current().nextDouble() < config.getRateLimitProbability()) {
            throw new RateLimitedException("429 Too Many Requests for " + prompt.id());
        }
        return new InferenceResponse("echo:" + prompt.text());
    }
}
