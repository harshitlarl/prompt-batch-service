package com.example.promptbatch.model;

/**
 * A single unit of work: one prompt within a batch.
 *
 * @param id   stable identifier, unique within its batch
 * @param text the prompt text sent to the inference client
 */
public record Prompt(String id, String text) {
}
