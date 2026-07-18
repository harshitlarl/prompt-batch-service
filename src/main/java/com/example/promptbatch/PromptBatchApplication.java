package com.example.promptbatch;

import com.example.promptbatch.api.BatchResource;
import com.example.promptbatch.constants.AppConstants;
import com.example.promptbatch.client.ExponentialJitterBackoff;
import com.example.promptbatch.client.InferenceClient;
import com.example.promptbatch.client.MeteredInferenceClient;
import com.example.promptbatch.client.MockInferenceClient;
import com.example.promptbatch.client.NoopRateLimiter;
import com.example.promptbatch.client.RateLimiter;
import com.example.promptbatch.client.RetryingInferenceClient;
import com.example.promptbatch.client.Sleeper;
import com.example.promptbatch.exception.BadInputExceptionMapper;
import com.example.promptbatch.exception.BatchNotFoundExceptionMapper;
import com.example.promptbatch.health.DatabaseHealthCheck;
import com.example.promptbatch.health.PingHealthCheck;
import com.example.promptbatch.health.StaleTaskRecoveryHealthCheck;
import com.example.promptbatch.health.WorkerPoolHealthCheck;
import com.example.promptbatch.ingest.JsonPromptSource;
import com.example.promptbatch.ingest.LinePromptSource;
import com.example.promptbatch.ingest.PromptSourceRegistry;
import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.model.Prompt;
import com.example.promptbatch.repository.BatchRepository;
import com.example.promptbatch.repository.InMemoryBatchRepository;
import com.example.promptbatch.resources.PingResource;
import com.example.promptbatch.service.Aggregator;
import com.example.promptbatch.service.BatchService;
import com.example.promptbatch.service.CompletionAggregator;
import com.example.promptbatch.store.InMemoryResultStore;
import com.example.promptbatch.store.JsonFileResultStore;
import com.example.promptbatch.store.ResultStore;
import com.example.promptbatch.store.postgres.PostgresBatchStore;
import com.example.promptbatch.worker.MeteredTaskExecutor;
import com.example.promptbatch.worker.PromptTask;
import com.example.promptbatch.worker.StaleTaskRecoveryService;
import com.example.promptbatch.worker.TaskExecutor;
import com.example.promptbatch.worker.ThreadPoolTaskExecutor;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import io.dropwizard.assets.AssetsBundle;
import io.federecio.dropwizard.swagger.SwaggerBundle;
import io.federecio.dropwizard.swagger.SwaggerBundleConfiguration;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterRegistration;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the prompt-batch-service Dropwizard application.
 *
 * <p>{@link #run} is the composition root - the <b>only</b> place that names concrete
 * implementations of the seams described in {@code docs/LLD.md} §4. Every other class holds
 * an interface, which is what keeps this system swappable and independently testable.
 */
public class PromptBatchApplication extends Application<PromptBatchConfiguration> {

    private static final Logger LOG = LoggerFactory.getLogger(PromptBatchApplication.class);

    public static void main(final String[] args) throws Exception {
        new PromptBatchApplication().run(args);
    }

    @Override
    public String getName() {
        return AppConstants.SERVICE_NAME;
    }

    @Override
    public void initialize(final Bootstrap<PromptBatchConfiguration> bootstrap) {
        // Lets every config/config-<env>.yml reference ${ENV_VAR:-default}, so the
        // per-environment YAML files stay the source of truth while secrets/hosts/ports
        // can still be overridden at deploy time without editing the files.
        bootstrap.setConfigurationSourceProvider(new SubstitutingSourceProvider(
                bootstrap.getConfigurationSourceProvider(),
                new EnvironmentVariableSubstitutor(false)));

        // Serves the OpenAPI spec + an interactive "try it out" Swagger UI at /swagger.
        bootstrap.addBundle(new SwaggerBundle<PromptBatchConfiguration>() {
            @Override
            protected SwaggerBundleConfiguration getSwaggerBundleConfiguration(
                    PromptBatchConfiguration configuration) {
                return configuration.getSwaggerBundleConfiguration();
            }
        });

        // Serves the hand-rolled "submit a batch" + "batch status dashboard" pages from
        // src/main/resources/assets at http://localhost:8080/ui (no separate web server needed;
        // the pages just fetch() the REST API below, same origin, no CORS hop required).
        bootstrap.addBundle(new AssetsBundle("/assets", "/ui", "index.html", "ui-assets"));
    }

    @Override
    public void run(final PromptBatchConfiguration config, final Environment env) {
        // --- pick implementations (the ONLY place that names concretes) ---
        BatchRepository repository;
        ResultStore resultStore;
        String storeType = config.getStore().getType();
        if (AppConstants.STORE_TYPE_POSTGRES.equalsIgnoreCase(storeType)) {
            // Shared, durable store: every app instance/container points at the same database
            // (e.g. a DigitalOcean Managed Database), so batch/result state - and therefore
            // GET /batches/{id} - is correct no matter which instance answers the request, and
            // survives any single container being killed/restarted/scaled down.
            PostgresBatchStore postgresStore = new PostgresBatchStore(
                    config.getStore().getDatabaseUrl(), config.getStore().getMaxPoolSize());
            env.lifecycle().manage(postgresStore); // closes the pool on graceful shutdown
            env.healthChecks().register(AppConstants.HEALTH_CHECK_DATABASE, new DatabaseHealthCheck(postgresStore));
            repository = postgresStore;
            resultStore = postgresStore;
        } else if (AppConstants.STORE_TYPE_JSON_FILE.equalsIgnoreCase(storeType)) {
            repository = new InMemoryBatchRepository();
            resultStore = new JsonFileResultStore(config.getStore().getDirectory());
        } else {
            repository = new InMemoryBatchRepository();
            resultStore = new InMemoryResultStore();
        }
        Aggregator aggregator = new CompletionAggregator(resultStore);

        // --- observability: every seam is metered by decoration, not by editing it (LLD.md §9.7) ---
        MetricRegistry metrics = env.metrics();

        // Primary pool: handles every freshly-submitted prompt (BatchService#enqueue). Sized for
        // normal request throughput.
        TaskExecutor taskExecutor = new MeteredTaskExecutor(
                new ThreadPoolTaskExecutor(config.getWorkerPool(), "prompt-worker"), metrics, "primary");
        env.lifecycle().manage(taskExecutor); // graceful drain on shutdown

        // Separate, small pool: only ever fed by StaleTaskRecoveryService (and the startup
        // recovery pass below) re-running prompts lost to a crashed/hung worker. Deliberately
        // kept apart from the primary pool - a flood of retries after an outage can never starve
        // or slow down normal incoming request processing, since the two pools have independent
        // bounded concurrency (config.getRetryWorkerPool(), default size 2).
        TaskExecutor retryTaskExecutor = new MeteredTaskExecutor(
                new ThreadPoolTaskExecutor(config.getRetryWorkerPool(), "retry-worker"), metrics, "retry");
        env.lifecycle().manage(retryTaskExecutor);

        RateLimiter rateLimiter = new NoopRateLimiter(); // -> RedisTokenBucketRateLimiter at scale
        InferenceClient transport = new MockInferenceClient(config.getMockEndpoint()); // -> HttpInferenceClient
        InferenceClient inferenceClient = new MeteredInferenceClient(
                new RetryingInferenceClient(
                        transport,
                        config.getRetry(),
                        new ExponentialJitterBackoff(config.getRetry(), new Random()),
                        rateLimiter,
                        Sleeper.REAL),
                metrics);

        // Gauges: point-in-time saturation/backlog numbers, pushed to every configured reporter
        // (see `metrics:` in config/config-*.yml) alongside the JVM metrics Dropwizard registers
        // by default.
        metrics.register(MetricRegistry.name(TaskExecutor.class, "primary", "activeCount"),
                (Gauge<Integer>) taskExecutor::activeCount);
        metrics.register(MetricRegistry.name(TaskExecutor.class, "primary", "queueSize"),
                (Gauge<Integer>) taskExecutor::queueSize);
        metrics.register(MetricRegistry.name(TaskExecutor.class, "retry", "activeCount"),
                (Gauge<Integer>) retryTaskExecutor::activeCount);
        metrics.register(MetricRegistry.name(TaskExecutor.class, "retry", "queueSize"),
                (Gauge<Integer>) retryTaskExecutor::queueSize);
        metrics.register(MetricRegistry.name(BatchRepository.class, "count"),
                (Gauge<Integer>) repository::count);

        PromptSourceRegistry ingestRegistry =
                new PromptSourceRegistry(List.of(new JsonPromptSource(), new LinePromptSource()));

        BatchService batchService = new BatchService(repository, taskExecutor, inferenceClient, aggregator);

        // Crash/container-restart recovery: only meaningful for a shared, durable repository -
        // if a container died mid-batch, whichever instance boots up next (itself, on restart,
        // or a fresh replacement scheduled by Docker/DigitalOcean App Platform) picks up exactly
        // the prompts that never produced a result and resubmits them. See
        // BatchRepository#pendingPrompts and BatchService#enqueue (which persists prompts via
        // BatchRepository#savePrompts before they're handed to the worker pool).
        //
        // This is only the *startup* half of fault tolerance - it requires some instance to
        // restart before it can act. StaleTaskRecoveryService below is the continuous half: it
        // keeps sweeping while every instance stays up, so a worker that crashes or hangs
        // mid-prompt gets its work picked up (by whichever live instance's next sweep runs
        // first) without waiting for anything to restart at all.
        if (repository instanceof PostgresBatchStore) {
            recoverInFlightBatches(repository, taskExecutor, inferenceClient, aggregator);

            if (config.getRecovery().isEnabled()) {
                StaleTaskRecoveryService recoveryService = new StaleTaskRecoveryService(
                        repository, retryTaskExecutor, inferenceClient, aggregator, config.getRecovery());
                env.lifecycle().manage(recoveryService);
                env.healthChecks().register(
                        AppConstants.HEALTH_CHECK_STALE_TASK_RECOVERY,
                        new StaleTaskRecoveryHealthCheck(recoveryService));
            }
        }

        // --- edge: CORS (lets a plain HTML/JS page on any origin call this API directly) ---
        final FilterRegistration.Dynamic cors = env.servlets().addFilter("CORS", CrossOriginFilter.class);
        cors.setInitParameter(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, "*");
        cors.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, "GET,POST,PUT,DELETE,OPTIONS,HEAD");
        cors.setInitParameter(CrossOriginFilter.ALLOWED_HEADERS_PARAM,
                "Content-Type,Authorization,X-Requested-With,Content-Length,Accept,Origin");
        cors.setInitParameter(CrossOriginFilter.ALLOW_CREDENTIALS_PARAM, "false");
        cors.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, "/*");

        // --- edge: REST + health + error mapping ---
        env.jersey().register(new PingResource());
        env.jersey().register(new BatchResource(batchService, ingestRegistry));
        env.jersey().register(new BatchNotFoundExceptionMapper());
        env.jersey().register(new BadInputExceptionMapper());

        env.healthChecks().register(AppConstants.HEALTH_CHECK_PING, new PingHealthCheck());
        env.healthChecks().register(AppConstants.HEALTH_CHECK_WORKER_POOL, new WorkerPoolHealthCheck(taskExecutor));
        env.healthChecks().register(
                AppConstants.HEALTH_CHECK_RETRY_WORKER_POOL, new WorkerPoolHealthCheck(retryTaskExecutor));
    }

    /**
     * Scans every batch that isn't yet {@code COMPLETED}/{@code FAILED} and resubmits any
     * prompt that was recorded (via {@code savePrompts}) but never produced a result - i.e. work
     * left unfinished because the container that originally enqueued it died before finishing.
     * Runs once at startup on every instance, so whichever instance comes back up (this one, or
     * a fresh replacement) is the one that resumes the batch. Uses the batch object returned by
     * {@code repository.find} (not the lightweight one from {@code listAll}) so its in-memory
     * succeeded/failed counters continue accumulating correctly as the re-submitted prompts
     * complete.
     */
    private void recoverInFlightBatches(
            BatchRepository repository,
            TaskExecutor taskExecutor,
            InferenceClient inferenceClient,
            Aggregator aggregator) {
        for (Batch summary : repository.listAll()) {
            if (summary.status() == BatchStatus.COMPLETED || summary.status() == BatchStatus.FAILED) {
                continue;
            }
            List<Prompt> pending = repository.pendingPrompts(summary.id());
            if (pending.isEmpty()) {
                continue;
            }
            Batch batch = repository.find(summary.id()).orElse(summary);
            LOG.info(
                    "Recovering batch {}: resubmitting {} prompt(s) left unfinished by a previous "
                            + "container ({} of {} already completed)",
                    batch.id(), pending.size(), batch.completed(), batch.total());
            for (Prompt prompt : pending) {
                repository.recordAttempt(batch.id(), prompt.id());
                taskExecutor.submit(new PromptTask(batch, prompt, inferenceClient, aggregator));
            }
        }
    }
}
