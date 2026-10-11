package dev.portfolio.ledger.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for API tests: the full application on a random port, talking to real PostgreSQL.
 * All subclasses share one Spring context and one database; tests use fresh accounts and
 * unique idempotency keys so they do not depend on each other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    private static final String DATABASE = TestPostgres.get().createDatabase();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres postgres = TestPostgres.get();
        registry.add("spring.datasource.url", () -> postgres.jdbcUrl(DATABASE));
        registry.add("spring.datasource.username", postgres::username);
        registry.add("spring.datasource.password", postgres::password);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    protected LedgerClient api;

    @Autowired
    void setUpClient(TestRestTemplate rest) {
        this.api = new LedgerClient(rest);
    }

    protected int countRows(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
