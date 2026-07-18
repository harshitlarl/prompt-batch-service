package com.example.promptbatch.exception;

/** Thrown when a submitted payload can't be parsed into prompts - mapped to HTTP 400. */
public class BadInputException extends RuntimeException {

    public BadInputException(String message) {
        super(message);
    }
}
