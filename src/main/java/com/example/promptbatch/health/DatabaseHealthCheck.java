package com.example.promptbatch.health;

import com.codahale.metrics.health.HealthCheck;
import com.example.promptbatch.store.postgres.PostgresBatchStore;

/**
 * Backs the admin {@code /healthcheck} endpoint with real DB connectivity when the shared
 * Postgres store is in use. DigitalOcean App Platform (and any other orchestrator polling this
 * endpoint) uses this to decide whether an instance is unhealthy and should be restarted/taken
 * out of rotation - which is what turns "container can't reach the database" into an automatic
 * container restart instead of a silently-broken instance.
 */
public class DatabaseHealthCheck extends HealthCheck {

    private final PostgresBatchStore store;

    public DatabaseHealthCheck(PostgresBatchStore store) {
        this.store = store;
    }

    @Override
    protected Result check() {
        return store.isHealthy()
                ? Result.healthy("Postgres connection pool is reachable")
                : Result.unhealthy("Postgres connection pool is not reachable");
    }
}
