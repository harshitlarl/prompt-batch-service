package com.example.promptbatch.store.sqlite;

import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.model.PromptOutcome;
import com.example.promptbatch.model.PromptResult;
import com.example.promptbatch.repository.BatchRepository;
import com.example.promptbatch.store.BatchResults;
import com.example.promptbatch.store.ResultStore;
import io.dropwizard.lifecycle.Managed;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable {@link BatchRepository} + {@link ResultStore} backed by a single SQLite file
 * (LLD.md §4 seams S4/S5, unified here since both need the same crash-safe on-disk source of
 * truth). Every state change - batch creation, each finished prompt, and finalization - is
 * written through immediately, so {@code GET /batches/{id}} and
 * {@code GET /batches/{id}/results} can be served correctly even after a process
 * crash/restart: just re-open the same file, no warm in-memory cache required.
 *
 * <p>All access is serialized behind {@link #lock} because sqlite-jdbc only tolerates one
 * writer at a time on a single connection; that's fine here since each write is one small
 * upsert per finished prompt, never on the inference hot path.
 */
public class SqliteBatchStore implements BatchRepository, ResultStore, Managed {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteBatchStore.class);

    private final Object lock = new Object();
    private final Connection connection;

    public SqliteBatchStore(String path) {
        try {
            Path file = Path.of(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
            }
            createSchema();
            LOG.info("Opened SQLite batch store at {}", file.toAbsolutePath());
        } catch (ClassNotFoundException | IOException | SQLException e) {
            throw new UncheckedIOException(
                    "Unable to open SQLite store at " + path, new IOException(e));
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS batches (
                        id TEXT PRIMARY KEY,
                        total INTEGER NOT NULL,
                        succeeded INTEGER NOT NULL DEFAULT 0,
                        failed INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        finished_at TEXT
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
        }
    }

    // --- BatchRepository ---

    @Override
    public void save(Batch batch) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO batches (id, total, succeeded, failed, status, created_at, finished_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
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
                ps.setString(6, batch.createdAt().toString());
                ps.setString(7, batch.finishedAt() == null ? null : batch.finishedAt().toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new SqliteStoreException("Failed to save batch " + batch.id(), e);
            }
        }
    }

    @Override
    public Optional<Batch> find(String id) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
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
                    Instant createdAt = Instant.parse(rs.getString("created_at"));
                    String finishedAtRaw = rs.getString("finished_at");
                    Instant finishedAt = finishedAtRaw == null ? null : Instant.parse(finishedAtRaw);
                    List<PromptResult> results = readResults(id);
                    return Optional.of(Batch.restore(
                            id, total, succeeded, failed, status, createdAt, finishedAt, results));
                }
            } catch (SQLException e) {
                throw new SqliteStoreException("Failed to load batch " + id, e);
            }
        }
    }

    @Override
    public int count() {
        synchronized (lock) {
            try (Statement st = connection.createStatement();
                    ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM batches")) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException e) {
                throw new SqliteStoreException("Failed to count batches", e);
            }
        }
    }

    @Override
    public List<Batch> listAll() {
        synchronized (lock) {
            List<Batch> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, total, succeeded, failed, status, created_at, finished_at "
                            + "FROM batches ORDER BY created_at DESC")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString("id");
                        int total = rs.getInt("total");
                        int succeeded = rs.getInt("succeeded");
                        int failed = rs.getInt("failed");
                        BatchStatus status = BatchStatus.valueOf(rs.getString("status"));
                        Instant createdAt = Instant.parse(rs.getString("created_at"));
                        String finishedAtRaw = rs.getString("finished_at");
                        Instant finishedAt = finishedAtRaw == null ? null : Instant.parse(finishedAtRaw);
                        // Listing is a lightweight overview, so per-prompt results aren't loaded here -
                        // fetch GET /batches/{id}/results for those.
                        result.add(Batch.restore(
                                id, total, succeeded, failed, status, createdAt, finishedAt, List.of()));
                    }
                }
            } catch (SQLException e) {
                throw new SqliteStoreException("Failed to list batches", e);
            }
            return result;
        }
    }

    // --- ResultStore ---

    @Override
    public void write(String batchId, PromptResult result) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO prompt_results (batch_id, prompt_id, outcome, output, failure_reason, attempts)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(batch_id, prompt_id) DO UPDATE SET
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
            } catch (SQLException e) {
                throw new SqliteStoreException(
                        "Failed to write result " + result.promptId() + " for batch " + batchId, e);
            }

            // Mirrors Batch#recordResult: exactly one counter increment per finished prompt, so
            // succeeded/failed here always match the live in-memory Batch, restart or not.
            String counterColumn = result.isSuccess() ? "succeeded" : "failed";
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE batches SET " + counterColumn + " = " + counterColumn + " + 1 WHERE id = ?")) {
                ps.setString(1, batchId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new SqliteStoreException(
                        "Failed to update counters for batch " + batchId, e);
            }
        }
    }

    @Override
    public void finalizeBatch(Batch batch) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE batches SET status = ?, finished_at = ? WHERE id = ?")) {
                ps.setString(1, batch.status().name());
                ps.setString(2, batch.finishedAt() == null ? null : batch.finishedAt().toString());
                ps.setString(3, batch.id());
                ps.executeUpdate();
                LOG.info("Persisted final status for batch {}: {}", batch.id(), batch.status());
            } catch (SQLException e) {
                throw new SqliteStoreException("Failed to finalize batch " + batch.id(), e);
            }
        }
    }

    @Override
    public Optional<BatchResults> read(String batchId) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT status FROM batches WHERE id = ?")) {
                ps.setString(1, batchId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    String status = rs.getString("status");
                    return Optional.of(new BatchResults(batchId, status, readResults(batchId)));
                }
            } catch (SQLException e) {
                throw new SqliteStoreException("Failed to read results for batch " + batchId, e);
            }
        }
    }

    private List<PromptResult> readResults(String batchId) throws SQLException {
        List<PromptResult> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
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
        // Connection is opened eagerly in the constructor so the store is usable as soon as
        // it's constructed (and so app startup fails fast if the file/path is bad).
    }

    @Override
    public void stop() {
        synchronized (lock) {
            try {
                connection.close();
                LOG.info("Closed SQLite batch store connection");
            } catch (SQLException e) {
                LOG.warn("Failed to close SQLite batch store connection cleanly", e);
            }
        }
    }
}
