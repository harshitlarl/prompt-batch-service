package com.example.promptbatch.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PingHealthCheckTest {

    @Test
    void checkReportsHealthy() throws Exception {
        PingHealthCheck healthCheck = new PingHealthCheck();

        var result = healthCheck.execute();

        assertThat(result.isHealthy()).isTrue();
    }
}
