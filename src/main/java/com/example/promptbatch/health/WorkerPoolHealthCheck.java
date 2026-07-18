package com.example.promptbatch.health;

import com.codahale.metrics.health.HealthCheck;
import com.example.promptbatch.worker.TaskExecutor;

/** Surfaces worker pool saturation (active threads / queued tasks) on the admin health page. */
public class WorkerPoolHealthCheck extends HealthCheck {

    private final TaskExecutor taskExecutor;

    public WorkerPoolHealthCheck(TaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    @Override
    protected Result check() {
        return Result.healthy(
                "active=%d, queued=%d".formatted(taskExecutor.activeCount(), taskExecutor.queueSize()));
    }
}
