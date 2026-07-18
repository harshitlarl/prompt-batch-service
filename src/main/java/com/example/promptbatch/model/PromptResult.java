package com.example.promptbatch.model;

/**
 * The terminal outcome of processing one {@link Prompt}: either a successful inference
 * output, or a failure reason. Never both.
 */
public record PromptResult(
        String promptId,
        PromptOutcome outcome,
        String output,
        String failureReason,
        int attempts) {

    public static PromptResult success(String promptId, String output, int attempts) {
        return new PromptResult(promptId, PromptOutcome.SUCCESS, output, null, attempts);
    }

    public static PromptResult failure(String promptId, String reason, int attempts) {
        return new PromptResult(promptId, PromptOutcome.FAILED, null, reason, attempts);
    }

    public boolean isSuccess() {
        return outcome == PromptOutcome.SUCCESS;
    }
}
