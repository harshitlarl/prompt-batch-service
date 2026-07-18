package com.example.promptbatch.exception;

/** Thrown when a batch id has no known {@code Batch} - mapped to HTTP 404. */
public class BatchNotFoundException extends RuntimeException {

    public BatchNotFoundException(String id) {
        super("Batch not found: " + id);
    }
}
