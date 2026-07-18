package com.example.promptbatch.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.promptbatch.worker.TaskExecutor;
import org.junit.jupiter.api.Test;

class WorkerPoolHealthCheckTest {

    @Test
    void reportsActiveAndQueuedCountsAsHealthy() throws Exception {
        TaskExecutor executor = mock(TaskExecutor.class);
        when(executor.activeCount()).thenReturn(3);
        when(executor.queueSize()).thenReturn(7);

        var result = new WorkerPoolHealthCheck(executor).check();

        assertThat(result.isHealthy()).isTrue();
        assertThat(result.getMessage()).contains("active=3", "queued=7");
    }
}
