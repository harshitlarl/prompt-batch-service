package com.example.promptbatch.constants;

/** Shared string constants used across the application wiring and config defaults. */
public final class AppConstants {

    public static final String SERVICE_NAME = "prompt-batch-service";

    public static final String STORE_TYPE_IN_MEMORY = "in-memory";
    public static final String STORE_TYPE_JSON_FILE = "json-file";
    public static final String STORE_TYPE_POSTGRES = "postgres";

    public static final String HEALTH_CHECK_PING = "ping";
    public static final String HEALTH_CHECK_WORKER_POOL = "workerPool";
    public static final String HEALTH_CHECK_RETRY_WORKER_POOL = "retryWorkerPool";
    public static final String HEALTH_CHECK_DATABASE = "database";
    public static final String HEALTH_CHECK_STALE_TASK_RECOVERY = "staleTaskRecovery";

    public static final String HEALTH_MESSAGE_UP = SERVICE_NAME + " is up";

    private AppConstants() {}
}
