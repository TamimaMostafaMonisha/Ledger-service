package dev.portfolio.ledger.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The PostgreSQL server used by every test.
 *
 * <p>By default a Testcontainers {@code postgres:17-alpine} container is started once per JVM.
 * If {@code LEDGER_TEST_PG_HOST} is set, an already running PostgreSQL server is used instead
 * (for machines without Docker). Either way the tests run against real PostgreSQL, never H2.
 * The user must be allowed to create databases.
 */
public final class TestPostgres {

    private static TestPostgres instance;

    private final String host;
    private final int port;
    private final String username;
    private final String password;

    private TestPostgres(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    public static synchronized TestPostgres get() {
        if (instance == null) {
            instance = System.getenv("LEDGER_TEST_PG_HOST") != null ? external() : container();
        }
        return instance;
    }

    private static TestPostgres external() {
        return new TestPostgres(
                System.getenv("LEDGER_TEST_PG_HOST"),
                Integer.parseInt(env("LEDGER_TEST_PG_PORT", "5432")),
                env("LEDGER_TEST_PG_USER", "postgres"),
                env("LEDGER_TEST_PG_PASSWORD", ""));
    }

    @SuppressWarnings("resource") // Singleton for the whole JVM; Testcontainers' reaper removes it afterwards.
    private static TestPostgres container() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        return new TestPostgres(postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                postgres.getUsername(), postgres.getPassword());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Creates an empty database with a unique name, so a test can prove a migration from scratch. */
    public String createDatabase() {
        String name = "ledger_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(jdbcUrl("postgres"), username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create test database " + name, e);
        }
        return name;
    }

    public String jdbcUrl(String database) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }
}
