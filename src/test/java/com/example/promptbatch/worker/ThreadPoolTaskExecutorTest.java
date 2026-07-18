package com.example.promptbatch.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.promptbatch.config.WorkerPoolConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Verifies the concurrency ceiling (N1) and graceful shutdown behavior. */
class ThreadPoolTaskExecutorTest {

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (executor != null) {
            executor.stop();
        }
    }

    @Test
    void neverExceedsConfiguredConcurrencyEvenWithManyMoreTasksThanThreads() throws InterruptedException {
        WorkerPoolConfig config = new WorkerPoolConfig();
        config.setSize(4);
        config.setQueueCapacity(1_000);
        executor = new ThreadPoolTaskExecutor(config);
        executor.start();

        int taskCount = 200;
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxObservedInFlight = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(taskCount);

        for (int i = 0; i < taskCount; i++) {
            executor.submit(() -> {
                int current = inFlight.incrementAndGet();
                maxObservedInFlight.getAndUpdate(prev -> Math.max(prev, current));
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                    done.countDown();
                }
            });
        }

        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(maxObservedInFlight.get()).isLessThanOrEqualTo(4);
    }

    @Test
    void allSubmittedTasksReachTerminalStateEvenWhenQueueIsSmall() throws InterruptedException {
        WorkerPoolConfig config = new WorkerPoolConfig();
        config.setSize(2);
        config.setQueueCapacity(2); // deliberately small to exercise CallerRunsPolicy backpressure
        executor = new ThreadPoolTaskExecutor(config);
        executor.start();

        int taskCount = 50;
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(taskCount);
        for (int i = 0; i < taskCount; i++) {
            executor.submit(() -> {
                completed.incrementAndGet();
                done.countDown();
            });
        }

        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(completed.get()).isEqualTo(taskCount);
    }

    @Test
    void stopDrainsInFlightWorkBeforeReturning() throws InterruptedException {
        WorkerPoolConfig config = new WorkerPoolConfig();
        config.setSize(2);
        config.setQueueCapacity(10);
        executor = new ThreadPoolTaskExecutor(config);
        executor.start();

        AtomicInteger completed = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                completed.incrementAndGet();
            });
        }

        executor.stop();
        assertThat(completed.get()).isEqualTo(5);
    }
}
