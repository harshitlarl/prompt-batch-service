package com.example.promptbatch.store.postgres;

import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.model.Prompt;
import com.example.promptbatch.model.PromptOutcome;
import com.example.promptbatch.model.PromptRecoveryCandidate;
import com.example.promptbatch.model.PromptResult;
import com.example.promptbatch.repository.BatchRepository;
import com.example.promptbatch.store.BatchResults;
import com.example.promptbatch.store.ResultStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.dropwizard.lifecycle.Managed;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable, <b>shared</b> {@link BatchRepository} + {@link ResultStore} backed by Postgres (e.g.
 * a DigitalOcean Managed Database). Unlike a single-process file store, this is designed to be
 * opened by <b>many app containers at once</b>, all pointed at the same database - which is
 * what makes horizontal scaling meaningful: every instance sees the same batch/result state
 * regardless of which instance is answering a given request.
 *
 * <p>It also records the full set of submitted prompts per batch ({@code batch_prompts}, via
 * {@link #savePrompts}) so that if the container processing a batch is killed mid-flight, any
 * instance's startup recovery pass (see {@code PromptBatchApplication}) can find the prompts
 * that never produced a result ({@link #pendingPrompts}) and resubmit just those, rather than
 * losing the batch or rerunning already-finished work.
 *
 * <p>Uses a HikariCP connection pool, safe for concurrent access from many worker threads.
 */
public class PostgresBatchStore implements BatchRepository, ResultStore, Managed {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresBatchStore.class);

    private final HikariDataSource dataSource;

    public PostgresBatchStore(String databaseUrl, int maxPoolSize) {
        PostgresConnectionString parsed = PostgresConnectionString.parse(databaseUrl);
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(parsed.jdbcUrl());
        if (parsed.username() != null) {
            hikariConfig.setUsername(parsed.username());
        }
        if (parsed.password() != null) {
            hikariConfig.setPassword(parsed.password());
        }
        hikariConfig.setMaximumPoolSize(maxPoolSize);
        hikariConfig.setPoolName("prompt-batch-postgres");
        // Managed DBs on a small droplet drop idle connections aggressively; fail fast and let
        // Hikari reconnect rather than hand out a dead connection.
        hikariConfig.setKeepaliveTime(30_000);
        hikariConfig.setInitializationFailTimeout(-1); // don't block app startup if DB is briefly unreachable
        this.dataSource = new HikariDataSource(hikariConfig);
        createSchema();
        LOG.info("Connected Postgres batch store to {}", parsed.jdbcUrl());
    }

    /** Exposed so {@code PromptBatchApplication} can register a DB-connectivity health check. */
    public boolean isHealthy() {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    private void createSchema() {
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS batches (
                        id TEXT PRIMARY KEY,
                        total INTEGER NOT NULL,
                        succeeded INTEGER NOT NULL DEFAULT 0,
                        failed INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL,
                        finished_at TIMESTAMPTZ
                    )
                    """);
            st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS prompt_results (
                        batch_id TEXT NOT NULL REFERENCES batches(id),
                        prompt_id TEXT NOT NULL,
                        outcome TEXT NOT NULL,
                        output TEXT,
                        failure_reason TEXT,
                        attempts INTEGER NOT NULL,
                        PRIMARY KEY (batch_id, prompt_id)
                    )
                    """);
            // Backs crash recovery: every prompt a batch was submitted with, independent of
            // whether it has finished yet. attempt_count/last_attempted_at back the background
            // reconciliation sweep (StaleTaskRecoveryService): they're how it tells "never
            // claimed" / "claimed but its worker died or hung" apart from "currently being
            // worked on normally", without needing any heartbeat from the worker itself.
            st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS batch_prompts (
                        batch_id TEXT NOT NULL REFERENCES batches(id),
                        prompt_id TEXT NOT NULL,
                        prompt_text TEXT NOT NULL,
                        attempt_count INTEGER NOT NULL DEFAULT 0,
                        last_attempted_at TIMESTAMPTZ,
                        PRIMARY KEY (batch_id, prompt_id)
                    )
                    """);
            // Additive, idempotent - safe to run against a database created before these
            // columns existed.
            st.execute("ALTER TABLE batch_prompts ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0");
            st.execute("ALTER TABLE batch_prompts ADD COLUMN IF NOT EXISTS last_attempted_at TIMESTAMPTZ");
        } catch (SQLException e) {
            throw new PostgresStoreException("Failed to initialize Postgres schema", e);
        }
    }

    // --- BatchRepository ---

    @Override
    public void save(Batch batch) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        """
                        INSERT INTO batches (id, total, succeeded, failed, status, created_at, finished_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET
                            total = excluded.total,
                            succeeded = excluded.succeeded,
                            failed = excluded.failed,
                            status = excluded.status,
                            created_at = excluded.created_at,
                            finished_at = excluded.finished_at
                        """)) {
            ps.setString(1, batch.id());
            ps.setInt(2, batch.total());
            ps.setInt(3, batch.succeeded());
            ps.setInt(4, batch.failed());
            ps.setString(5, batch.status().name());
            ps.setObject(6, batch.createdAt().atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(7, batch.finishedAt() == null ? null : batch.finishedAt().atOffset(java.time.ZoneOffset.UTC));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PostgresStoreException("Failed to save batch " + batch.id(), e);
        }
    }

    @Override
    public Optional<Batch> find(String id) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT total, succeeded, failed, status, created_at, finished_at "
                                + "FROM batches WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                int total = rs.getInt("total");
                int succeeded = rs.getInt("succeeded");
                int failed = rs.getInt("failed");
                BatchStatus status = BatchStatus.valueOf(rs.getString("status"));
                Instant createdAt = rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant();
                java.time.OffsetDateTime finishedAtRaw =
                        rs.getObject("finished_at", java.time.OffsetDateTime.class);
                Instant finishedAt = finishedAtRaw == null ? null : finishedAtRaw.toInstant();
                List<PromptResult> results = readResults(c, id);
                return Optional.of(Batch.restore(
                        id, total, succeeded, failed, status, createdAt, finishedAt, results));
            }
        } catch (SQLException e) {
            throw new PostgresStoreException("Failed to load batch " + id, e);
        }
    }

    @Override
    public int count() {
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM batches")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new PostgresStoreException("Failed to count batches", e);
        }
    }

    @Override
    public List<Batch> listAll() {
        List<Batch> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT id, total, succeeded, failed, status, created_at, finished_at "
                                + "FROM batches ORDER BY created_at DESC");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("id");
                int total = rs.getInt("total");
                int succeeded = rs.getInt("succeeded");
                int failed = rs.getInt("failed");
                BatchStatus status = BatchStatus.valueOf(rs.getString("status"));
                Instant createdAt = rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant();
                java.time.OffsetDateTime finishedAtRaw =
                        rs.getObject("finished_at", java.time.OffsetDateTime.class);
                Instant finishedAt = finishedAtRaw == null ? null : finishedAtRaw.toInstant();
                // Lightweight overview - per-prompt results aren't loaded here; fetch
                // GET /batches/{id}/results for those.
                result.add(Batch.restore(id, total, succeeded, failed, status, createdAt, finishedAt, List.of()));
            }
        } catch (SQLException e) {
            throw new PostgresStoreException("Failed to list batches", e);
        }
        return result;
    }

    @Override
    public void savePrompts(String batchId, List<Prompt> prompts) {
        if (prompts.isEmpty()) {
            return;
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        """
                        INSERT INTO batch_prompts (batch_id, prompt_id, prompt_text)
                        VALUES (?, ?, ?)
                        ON CONFLICT (batch_id, prompt_id) DO NOTHING
                        """)) {
            c.setAutoCommit(false);
            for (Prompt prompt : prompts) {
                ps.setString(1, batchId);
                ps.setString(2, prompt.id());
                ps.setString(3, prompt.text());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        } catch (SQLException e) {
            throw new PostgresStoreException("Failed to save prompts for batch " + batchId, e);
        }
    }

    @Override
    public List<Prompt> pendingPrompts(String batchId) {
        List<Prompt> pending = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        """
                        SELECT bp.prompt_id, bp.prompt_text
                        FROM batch_prompts bp
                        LEFT JOIN prompt_results pr
                            ON pr.batch_id = bp.batch_id AND pr.prompt_id = bp.prompt_id
                        WHERE bp.batch_id = ? AND pr.prompt_id IS NULL
                        ORDER BY bp.prompt_id
                        """)) {
            ps.setString(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pending.add(new Prompt(rs.getString("prompt_id"), rs.getString("prompt_text")));
                }
            }
        } catch (SQLException e) {
            throw new PostgresStoreException("Failed to load pending prompts for batch " + batchId, e);
        }
        return pending;
    }

    @Override
    public void recordAttempt(String batchId, String promptId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        """
                        UPDATE batch_prompts
                        SET attempt_count = attempt_count + 1, last_attempted_at = now()
                        WHERE batch_id = ? AND prompt_id = ?
                        """)) {
            ps.setString(1, batchId);
            ps.setString(2, promptId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PostgresStoreException(
                    "Failed to record attempt for prompt " + promptId + " in batch " + batchId, e);
        }
    }

    @Override
    public List<PromptRecoveryCandidate> staleUnfinishedPrompts(Duration staleAfter) {
        List<PromptRecoveryCandidate> candidates = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        """
                        SELECT bp.batch_id, bp.prompt_id, bp.prompt_text, bp.attempt_count
                        FROM batch_prompts bp
                        JOIN batches b ON b.id = bp.batch_id
                        LEFT JOIN prompt_results pr
                            ON pr.batch_id = bp.batch_id AND pr.prompt_id = bp.prompt_id
                        WHERE pr.prompt_id IS NULL
                          AND b.status NOT IN ('COMPLETED', 'FAILED')
                          AND (bp.last_attempted_at IS NULL OR bp.last_attempted_at < ?)
                        ORDER BY bp.last_attempted_at NULLS FIRST
                        LIMIT 500
                        """)) {
            ps.setObject(1, Instant.now().minus(staleAfter).atOffset(java.time.ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new PromptRecoveryCandidate(
                            rs.getString("batch_id"),
                            new Prompt(rs.getString("prompt_id"), rs.getString("prompt_text")),
                            rs.getInt("attempt_count")));
                }
            }
        } catch (SQLException e) {
            throw new PostgresStoreException("Failed to scan for stale unfinished prompts", e);
        }
        return candidates;
    }

    // --- ResultStore ---

    @Override
    public void write(String batchId, PromptResult result) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    """
                    INSERT INTO prompt_results (batch_id, prompt_id, outcome, output, failure_reason, attempts)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (batch_id, prompt_id) DO UPDATE SET
                        outcome = excluded.outcome,
                        output = excluded.output,
                        failure_reason = excluded.failure_reason,
                        attempts = excluded.attempts
                    """)) {
                ps.setString(1, batchId);
                ps.setString(2, result.promptId());
                ps.setString(3, result.outcome().name());
                ps.setString(4, result.output());
                ps.setString(5, result.failureReason());
                ps.setInt(6, result.attempts());
                ps.executeUpdate();
            }

            // Mirrors Batch#recordResult: exactly one counter increment per finished prompt, so
            // succeeded/failed here always match the live in-memory Batch, restart or not, even
            // when multiple app instances are writing concurrently (the UPDATE is atomic per row).
            String counterColumn = result.isSuccess() ? "succeeded" : "failed";
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE batches SET " + counterColumn + " = " + counterColumn + " + 1 WHERE id = ?")) {
                ps.setString(1, batchId);
                ps.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            throw new PostgresStoreException(
                    "Failed to write result " + result.promptId() + " for batch " + batchId, e);
        }
    }

    @Override
    public void finalizeBatch(Batch batch) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE batches SET status = ?, finished_at = ? WHERE id = ?")) {
            ps.setString(1, batch.status().name());
            ps.setObject(2, batch.finishedAt() == null ? null : batch.finishedAt().atOffset(java.time.ZoneOffset.UTC));
            ps.setString(3, batch.id());
            ps.executeUpdate();
            LOG.info("Persisted final status for batch {}: {}", batch.id(), batch.status());
        } catch (SQLException e) {
            throw new PostgresStoreException("Failed to finalize batch " + batch.id(), e);
        }
    }

    @Override
    public Optional<BatchResults> read(String batchId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT status FROM batches WHERE id = ?")) {
            ps.setString(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String status = rs.getString("status");
                return Optional.of(new BatchResults(batchId, status, readResults(c, batchId)));
            }
        } catch (SQLException e) {
            throw new PostgresStoreException("Failed to read results for batch " + batchId, e);
        }
    }

    private List<PromptResult> readResults(Connection c, String batchId) throws SQLException {
        List<PromptResult> results = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT prompt_id, outcome, output, failure_reason, attempts "
                        + "FROM prompt_results WHERE batch_id = ?")) {
            ps.setString(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new PromptResult(
                            rs.getString("prompt_id"),
                            PromptOutcome.valueOf(rs.getString("outcome")),
                            rs.getString("output"),
                            rs.getString("failure_reason"),
                            rs.getInt("attempts")));
                }
            }
        }
        return results;
    }

    // --- Managed (Dropwizard lifecycle) ---

    @Override
    public void start() {
        // Pool is opened eagerly in the constructor so the store is usable as soon as it's
        // constructed (and so app startup fails fast if the connection string is bad).
    }

    @Override
    public void stop() {
        dataSource.close();
        LOG.info("Closed Postgres batch store connection pool");
    }
}
