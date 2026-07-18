package com.example.promptbatch.worker;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;

/**
 * Decorator that records worker-pool throughput/latency metrics around an existing
 * {@link TaskExecutor}, without editing {@code ThreadPoolTaskExecutor} (LLD.md §9.7). Wraps the
 * submitted {@link Runnable} itself, so the recorded {@code duration} covers queue wait time plus
 * actual run time - i.e. what a caller polling batch progress actually experiences.
 *
 * <p>Registers, under the {@code taskExecutor} metric namespace:
 *
 * <ul>
 *   <li>{@code submitted} - a {@link Counter} of tasks handed to the pool.
 *   <li>{@code duration} - a {@link Timer} from submit to run-completion.
 *   <li>{@code completed} / {@code failed} - {@link Meter}s of how each task run finished.
 * </ul>
 *
 * <p>{@code activeCount()}/{@code queueSize()} and the {@link Managed} lifecycle simply delegate,
 * so this can wrap (or be wrapped by) any other {@link TaskExecutor} implementation.
 */
public class MeteredTaskExecutor implements TaskExecutor {

    private final TaskExecutor delegate;
    private final Counter submitted;
    private final Timer duration;
    private final Meter completed;
    private final Meter failed;

    public MeteredTaskExecutor(TaskExecutor delegate, MetricRegistry metrics) {
        this(delegate, metrics, "primary");
    }

    /**
     * @param poolName distinguishes this pool's metrics from any other {@code TaskExecutor} in
     *     the same registry - e.g. {@code "primary"} for fresh-request processing vs
     *     {@code "retry"} for the dedicated stuck/crashed-task recovery pool. Without this, two
     *     pools in the same process would silently share (and stomp on) the same metric names.
     */
    public MeteredTaskExecutor(TaskExecutor delegate, MetricRegistry metrics, String poolName) {
        this.delegate = delegate;
        this.submitted = metrics.counter(MetricRegistry.name(TaskExecutor.class, poolName, "submitted"));
        this.duration = metrics.timer(MetricRegistry.name(TaskExecutor.class, poolName, "duration"));
        this.completed = metrics.meter(MetricRegistry.name(TaskExecutor.class, poolName, "completed"));
        this.failed = metrics.meter(MetricRegistry.name(TaskExecutor.class, poolName, "failed"));
    }

    @Override
    public void submit(Runnable task) {
        submitted.inc();
        delegate.submit(() -> {
            Timer.Context timing = duration.time();
            try {
                task.run();
                completed.mark();
            } catch (RuntimeException e) {
                failed.mark();
                throw e;
            } finally {
                timing.stop();
            }
        });
    }

    @Override
    public int activeCount() {
        return delegate.activeCount();
    }

    @Override
    public int queueSize() {
        return delegate.queueSize();
    }

    @Override
    public void start() throws Exception {
        delegate.start();
    }

    @Override
    public void stop() throws Exception {
        delegate.stop();
    }
}
