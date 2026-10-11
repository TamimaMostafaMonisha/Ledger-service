package dev.portfolio.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.portfolio.ledger.support.TestPostgres;

/**
 * Starts the real application twice against one fresh database: the first start must run the
 * migration, the second must find it already applied, pass Hibernate validation and keep the data.
 */
class SchemaAndRestartTest {

    private final TestPostgres postgres = TestPostgres.get();

    @Test
    void migratesFreshDatabaseAndSurvivesRestart() {
        String database = postgres.createDatabase();

        try (ConfigurableApplicationContext app = start(database)) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            assertThat(appliedMigrations(jdbc)).containsExactly("1");
            assertThat(tables(jdbc)).contains("accounts", "transfers");
            jdbc.update("INSERT INTO accounts (id, label, currency, created_at) "
                    + "VALUES (gen_random_uuid(), 'Survives restart', 'BDT', now())");
        }

        try (ConfigurableApplicationContext app = start(database)) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            assertThat(appliedMigrations(jdbc)).containsExactly("1");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE label = 'Survives restart'",
                    Integer.class)).isEqualTo(1);
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

    private static List<String> appliedMigrations(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
    }

    private static List<String> tables(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
    }
}
