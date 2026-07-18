package com.example.promptbatch.store.postgres;

/** Wraps any {@link java.sql.SQLException} raised by {@link PostgresBatchStore} into an unchecked form. */
public class PostgresStoreException extends RuntimeException {

    public PostgresStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
