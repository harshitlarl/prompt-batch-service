package com.example.promptbatch.client;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.example.promptbatch.model.Prompt;

/**
 * Decorator that records {@link InferenceClient} call metrics without touching the retry/backoff
 * logic beneath it (LLD.md §9.7 - "add observability by decoration, not by editing business
 * classes"). Wired as the <b>outermost</b> layer of the client chain in the composition root, so
 * it observes the true end-to-end latency and outcome of every prompt - including whatever
 * retries happened underneath.
 *
 * <p>Registers, under the {@code inferenceClient} metric namespace:
 *
 * <ul>
 *   <li>{@code latency} - a {@link Timer} over every call (success or failure).
 *   <li>{@code success} / {@code rateLimited} / {@code failure} - {@link Meter}s of the terminal
 *       outcome, so dashboards can plot request rate alongside error/throttle rate.
 * </ul>
 */
public class MeteredInferenceClient implements InferenceClient {

    private final InferenceClient delegate;
    private final Timer latency;
    private final Meter success;
    private final Meter rateLimited;
    private final Meter failure;

    public MeteredInferenceClient(InferenceClient delegate, MetricRegistry metrics) {
        this.delegate = delegate;
        this.latency = metrics.timer(MetricRegistry.name(InferenceClient.class, "latency"));
        this.success = metrics.meter(MetricRegistry.name(InferenceClient.class, "success"));
        this.rateLimited = metrics.meter(MetricRegistry.name(InferenceClient.class, "rateLimited"));
        this.failure = metrics.meter(MetricRegistry.name(InferenceClient.class, "failure"));
    }

    @Override
    public InferenceResponse infer(Prompt prompt) {
        Timer.Context timing = latency.time();
        try {
            InferenceResponse response = delegate.infer(prompt);
            success.mark();
            return response;
        } catch (RateLimitedException e) {
            rateLimited.mark();
            throw e;
        } catch (RuntimeException e) {
            failure.mark();
            throw e;
        } finally {
            timing.stop();
        }
    }
}
