package com.example.promptbatch.store.postgres;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Turns the connection string DigitalOcean hands out for a Managed Postgres Database (and what
 * App Platform injects as {@code ${db.DATABASE_URL}}) -
 * {@code postgresql://user:password@host:port/dbname?sslmode=require} - into the pieces
 * HikariCP/pgjdbc actually want: a plain {@code jdbc:postgresql://...} URL plus separate
 * username/password. Also tolerates an already-{@code jdbc:}-prefixed URL for local/manual
 * setups where the caller just wants to pass a JDBC URL straight through.
 */
final class PostgresConnectionString {

    private final String jdbcUrl;
    private final String username;
    private final String password;

    private PostgresConnectionString(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    static PostgresConnectionString parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "store.databaseUrl must be set for store.type=postgres (e.g. via the "
                            + "DATABASE_URL env var from a DigitalOcean Managed Database)");
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("jdbc:")) {
            // Already a JDBC URL; pgjdbc will pull user/password out of it (or the caller sets
            // them as query params), so there's nothing left to split out here.
            return new PostgresConnectionString(trimmed, null, null);
        }
        try {
            // Normalize the "postgres://" alias some tools emit to "postgresql://" so URI parsing
            // below is uniform.
            String normalized = trimmed.startsWith("postgres://")
                    ? "postgresql://" + trimmed.substring("postgres://".length())
                    : trimmed;
            URI uri = new URI(normalized);
            String userInfo = uri.getUserInfo();
            String username = null;
            String password = null;
            if (userInfo != null) {
                int colon = userInfo.indexOf(':');
                username = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
                password = colon >= 0 ? userInfo.substring(colon + 1) : null;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
            String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + query;
            return new PostgresConnectionString(jdbcUrl, username, password);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Malformed store.databaseUrl: " + raw, e);
        }
    }

    String jdbcUrl() {
        return jdbcUrl;
    }

    String username() {
        return username;
    }

    String password() {
        return password;
    }
}
