package com.example.promptbatch.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.promptbatch.config.MockEndpointConfig;
import com.example.promptbatch.model.Prompt;
import org.junit.jupiter.api.Test;

class MockInferenceClientTest {

    @Test
    void neverThrowsWhenRateLimitProbabilityIsZero() {
        MockEndpointConfig config = new MockEndpointConfig();
        config.setRateLimitProbability(0.0);
        config.setBaseLatencyMs(0);
        MockInferenceClient client = new MockInferenceClient(config);

        for (int i = 0; i < 50; i++) {
            InferenceResponse response = client.infer(new Prompt("p" + i, "hello"));
            assertThat(response.output()).isEqualTo("echo:hello");
        }
    }

    @Test
    void alwaysThrowsRateLimitedWhenProbabilityIsOne() {
        MockEndpointConfig config = new MockEndpointConfig();
        config.setRateLimitProbability(1.0);
        config.setBaseLatencyMs(0);
        MockInferenceClient client = new MockInferenceClient(config);

        assertThatThrownBy(() -> client.infer(new Prompt("p1", "hello")))
                .isInstanceOf(RateLimitedException.class);
    }
}
