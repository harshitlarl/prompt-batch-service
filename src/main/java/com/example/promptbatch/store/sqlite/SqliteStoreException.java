package com.example.promptbatch.store.sqlite;

/** Wraps checked {@link java.sql.SQLException}s from {@link SqliteBatchStore} as unchecked. */
public class SqliteStoreException extends RuntimeException {

    public SqliteStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
