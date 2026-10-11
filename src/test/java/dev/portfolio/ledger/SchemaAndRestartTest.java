package dev.portfolio.ledger;

import static dev.portfolio.ledger.support.LedgerClient.transferJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.portfolio.ledger.support.LedgerClient;
import dev.portfolio.ledger.support.TestPostgres;

/**
 * Starts the real application twice against one fresh database: the first start must run the
 * migration, the second must find it already applied, pass Hibernate validation and keep the data.
 * Idempotency must also survive the restart, because the key lives in the database, not in memory.
 */
class SchemaAndRestartTest {

    private final TestPostgres postgres = TestPostgres.get();

    @Test
    void migratesFreshDatabaseAndKeepsDataAndIdempotencyAcrossRestart() {
        String database = postgres.createDatabase();
        String key = "restart-" + UUID.randomUUID();
        UUID source;
        UUID target;
        ResponseEntity<String> original;

        try (ConfigurableApplicationContext app = start(database)) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            assertThat(appliedMigrations(jdbc)).containsExactly("1");
            assertThat(tables(jdbc)).contains("accounts", "transfers");

            LedgerClient api = client(app);
            source = api.createAccount("Before restart A");
            target = api.createAccount("Before restart B");
            original = api.post("/transfers", transferJson(source, target, "10.0"), key);
            assertThat(original.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        try (ConfigurableApplicationContext app = start(database)) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            assertThat(appliedMigrations(jdbc)).containsExactly("1");

            LedgerClient api = client(app);
            assertThat(api.get("/accounts/" + source).getStatusCode()).isEqualTo(HttpStatus.OK);

            ResponseEntity<String> replay = api.post("/transfers", transferJson(source, target, "10.00"), key);
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(replay.getBody()).isEqualTo(original.getBody());
            assertThat(replay.getHeaders().getLocation().getPath())
                    .isEqualTo(original.getHeaders().getLocation().getPath());
            assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE idempotency_key = ?",
                    Integer.class, key)).isEqualTo(1);
        }
    }

    private ConfigurableApplicationContext start(String database) {
        // Command-line arguments outrank application.yml (builder .properties() would not).
        return new SpringApplicationBuilder(LedgerApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + postgres.jdbcUrl(database),
                "--spring.datasource.username=" + postgres.username(),
                "--spring.datasource.password=" + postgres.password());
    }

    private static LedgerClient client(ConfigurableApplicationContext app) {
        String port = app.getEnvironment().getProperty("local.server.port");
        return new LedgerClient(new TestRestTemplate(new RestTemplateBuilder().rootUri("http://localhost:" + port)));
    }

    private static List<String> appliedMigrations(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
    }

    private static List<String> tables(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
    }
}
