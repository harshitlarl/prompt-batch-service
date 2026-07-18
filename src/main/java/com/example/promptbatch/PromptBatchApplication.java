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
import com.example.promptbatch.health.PingHealthCheck;
import com.example.promptbatch.health.WorkerPoolHealthCheck;
import com.example.promptbatch.ingest.JsonPromptSource;
import com.example.promptbatch.ingest.LinePromptSource;
import com.example.promptbatch.ingest.PromptSourceRegistry;
import com.example.promptbatch.repository.BatchRepository;
import com.example.promptbatch.repository.InMemoryBatchRepository;
import com.example.promptbatch.resources.PingResource;
import com.example.promptbatch.service.Aggregator;
import com.example.promptbatch.service.BatchService;
import com.example.promptbatch.service.CompletionAggregator;
import com.example.promptbatch.store.InMemoryResultStore;
import com.example.promptbatch.store.JsonFileResultStore;
import com.example.promptbatch.store.ResultStore;
import com.example.promptbatch.store.sqlite.SqliteBatchStore;
import com.example.promptbatch.worker.MeteredTaskExecutor;
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

/**
 * Entry point for the prompt-batch-service Dropwizard application.
 *
 * <p>{@link #run} is the composition root - the <b>only</b> place that names concrete
 * implementations of the seams described in {@code docs/LLD.md} §4. Every other class holds
 * an interface, which is what keeps this system swappable and independently testable.
 */
public class PromptBatchApplication extends Application<PromptBatchConfiguration> {

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
        if (AppConstants.STORE_TYPE_SQLITE.equalsIgnoreCase(storeType)) {
            // One store, both seams: batch state and results live in the same SQLite file, so
            // GET /batches/{id} and GET /batches/{id}/results both survive a process crash.
            SqliteBatchStore sqliteStore = new SqliteBatchStore(config.getStore().getPath());
            env.lifecycle().manage(sqliteStore); // closes the connection on graceful shutdown
            repository = sqliteStore;
            resultStore = sqliteStore;
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

        TaskExecutor taskExecutor =
                new MeteredTaskExecutor(new ThreadPoolTaskExecutor(config.getWorkerPool()), metrics);
        env.lifecycle().manage(taskExecutor); // graceful drain on shutdown

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
        metrics.register(MetricRegistry.name(TaskExecutor.class, "activeCount"),
                (Gauge<Integer>) taskExecutor::activeCount);
        metrics.register(MetricRegistry.name(TaskExecutor.class, "queueSize"),
                (Gauge<Integer>) taskExecutor::queueSize);
        metrics.register(MetricRegistry.name(BatchRepository.class, "count"),
                (Gauge<Integer>) repository::count);

        PromptSourceRegistry ingestRegistry =
                new PromptSourceRegistry(List.of(new JsonPromptSource(), new LinePromptSource()));

        BatchService batchService = new BatchService(repository, taskExecutor, inferenceClient, aggregator);

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
    }
}
