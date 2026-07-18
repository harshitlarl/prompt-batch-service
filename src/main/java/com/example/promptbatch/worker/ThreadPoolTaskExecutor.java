package com.example.promptbatch.worker;

import com.example.promptbatch.config.WorkerPoolConfig;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * v1 implementation of {@link TaskExecutor}: a single, shared, <b>fixed-size</b>
 * {@link ThreadPoolExecutor} backed by a <b>bounded</b> queue (SOLUTIONING.md §4.3).
 *
 * <ul>
 *   <li>{@code corePoolSize == maxPoolSize} - at most {@code N} prompts are ever in flight.</li>
 *   <li>{@code ArrayBlockingQueue} (bounded) - submitted-but-not-started work is capped, so a
 *       huge batch cannot accumulate unbounded {@code Runnable}s and OOM the JVM.</li>
 *   <li>{@code CallerRunsPolicy} - when both pool and queue are full, the submitting thread
 *       runs the task itself; this throttles the producer instead of dropping work.</li>
 * </ul>
 *
 * <p>Registered with Dropwizard's managed lifecycle so in-flight prompts drain on shutdown.
 */
public class ThreadPoolTaskExecutor implements TaskExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ThreadPoolTaskExecutor.class);

    private final ThreadPoolExecutor executor;
    private final String threadNamePrefix;

    public ThreadPoolTaskExecutor(WorkerPoolConfig config) {
        this(config, "prompt-worker");
    }

    /**
     * @param threadNamePrefix distinguishes this pool's threads in thread dumps/logs from any
     *     other {@code ThreadPoolTaskExecutor} in the same process - e.g. {@code "prompt-worker"}
     *     for the primary pool handling fresh requests vs {@code "retry-worker"} for the small
     *     pool dedicated to re-running stuck/crashed/failed work (see
     *     {@code StaleTaskRecoveryService}), so it's obvious from a thread name alone which
     *     pool picked up a given task.
     */
    public ThreadPoolTaskExecutor(WorkerPoolConfig config, String threadNamePrefix) {
        this.threadNamePrefix = threadNamePrefix;
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, threadNamePrefix + "-" + n.incrementAndGet());
                t.setDaemon(false);
                return t;
            }
        };
        this.executor = new ThreadPoolExecutor(
                config.getSize(), config.getSize(),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.getQueueCapacity()),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Override
    public void submit(Runnable task) {
        executor.execute(task);
    }

    @Override
    public int activeCount() {
        return executor.getActiveCount();
    }

    @Override
    public int queueSize() {
        return executor.getQueue().size();
    }

    @Override
    public void start() {
        LOG.info("Worker pool '{}' started: {} threads", threadNamePrefix, executor.getCorePoolSize());
    }

    @Override
    public void stop() throws InterruptedException {
        LOG.info("Shutting down worker pool '{}'...", threadNamePrefix);
        executor.shutdown();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            LOG.warn("Pool '{}' did not drain in time; forcing shutdown", threadNamePrefix);
            executor.shutdownNow();
        }
        LOG.info("Worker pool '{}' stopped", threadNamePrefix);
    }
}
