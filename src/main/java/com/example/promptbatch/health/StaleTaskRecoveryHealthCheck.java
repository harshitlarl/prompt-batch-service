package com.example.promptbatch.health;

import com.codahale.metrics.health.HealthCheck;
import com.example.promptbatch.worker.StaleTaskRecoveryService;
import java.time.Instant;

/**
 * Surfaces whether the background stale-task recovery sweep is actually running (and what it
 * found last time) on the admin health page - so "is fault tolerance actually working" is a
 * question you can answer by hitting {@code /healthcheck} instead of just trusting the wiring.
 */
public class StaleTaskRecoveryHealthCheck extends HealthCheck {

    private final StaleTaskRecoveryService service;

    public StaleTaskRecoveryHealthCheck(StaleTaskRecoveryService service) {
        this.service = service;
    }

    @Override
    protected Result check() {
        Instant lastSweepAt = service.lastSweepAt();
        if (lastSweepAt == null) {
            return Result.healthy("no sweep has run yet");
        }
        return Result.healthy(
                "lastSweepAt=%s, lastResubmitted=%d, lastAbandoned=%d"
                        .formatted(lastSweepAt, service.lastRetriedCount(), service.lastAbandonedCount()));
    }
}
