package com.example.promptbatch.health;

import com.codahale.metrics.health.HealthCheck;
import com.example.promptbatch.constants.AppConstants;

/**
 * Trivial health check confirming the application context is alive. Will be
 * extended (or joined by additional checks) once persistence and the mock
 * inference client are introduced.
 */
public class PingHealthCheck extends HealthCheck {

    @Override
    protected Result check() {
        return Result.healthy(AppConstants.HEALTH_MESSAGE_UP);
    }
}
