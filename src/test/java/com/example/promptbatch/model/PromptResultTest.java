package com.example.promptbatch.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PromptResultTest {

    @Test
    void successFactoryProducesSuccessOutcome() {
        PromptResult result = PromptResult.success("p1", "output", 1);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outcome()).isEqualTo(PromptOutcome.SUCCESS);
        assertThat(result.output()).isEqualTo("output");
        assertThat(result.failureReason()).isNull();
    }

    @Test
    void failureFactoryProducesFailedOutcome() {
        PromptResult result = PromptResult.failure("p1", "reason", 3);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.outcome()).isEqualTo(PromptOutcome.FAILED);
        assertThat(result.failureReason()).isEqualTo("reason");
        assertThat(result.output()).isNull();
    }
}
