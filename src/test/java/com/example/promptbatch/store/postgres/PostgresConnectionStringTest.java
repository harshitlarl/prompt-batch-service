package com.example.promptbatch.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PostgresConnectionStringTest {

    @Test
    void parsesDigitalOceanStyleConnectionString() {
        PostgresConnectionString parsed = PostgresConnectionString.parse(
                "postgresql://doadmin:s3cr3t@db-postgresql-nyc1.b.db.ondigitalocean.com:25060/promptbatch?sslmode=require");

        assertThat(parsed.jdbcUrl())
                .isEqualTo("jdbc:postgresql://db-postgresql-nyc1.b.db.ondigitalocean.com:25060/promptbatch?sslmode=require");
        assertThat(parsed.username()).isEqualTo("doadmin");
        assertThat(parsed.password()).isEqualTo("s3cr3t");
    }

    @Test
    void normalizesPostgresSchemeAlias() {
        PostgresConnectionString parsed =
                PostgresConnectionString.parse("postgres://user:pw@localhost:5432/mydb");

        assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/mydb");
        assertThat(parsed.username()).isEqualTo("user");
        assertThat(parsed.password()).isEqualTo("pw");
    }

    @Test
    void passesThroughAlreadyJdbcPrefixedUrlsUnchanged() {
        PostgresConnectionString parsed =
                PostgresConnectionString.parse("jdbc:postgresql://localhost:5432/mydb?user=u&password=p");

        assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/mydb?user=u&password=p");
        assertThat(parsed.username()).isNull();
        assertThat(parsed.password()).isNull();
    }

    @Test
    void defaultsPortWhenMissing() {
        PostgresConnectionString parsed = PostgresConnectionString.parse("postgresql://user:pw@localhost/mydb");

        assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/mydb");
    }

    @Test
    void rejectsBlankConnectionString() {
        assertThatThrownBy(() -> PostgresConnectionString.parse(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("store.databaseUrl");
    }
}
