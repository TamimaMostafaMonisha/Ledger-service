package dev.portfolio.ledger.transfer;

import static dev.portfolio.ledger.support.LedgerClient.json;
import static dev.portfolio.ledger.support.LedgerClient.transferJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import dev.portfolio.ledger.support.IntegrationTest;

/** Plan item 7: simultaneous requests with one key must end in exactly one row and one answer. */
class TransferConcurrencyTest extends IntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    private DataSource dataSource;

    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    private UUID source;
    private UUID target;

    @BeforeEach
    void createAccounts() {
        source = api.createAccount("Concurrent A");
        target = api.createAccount("Concurrent B");
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    void simultaneousRequestsWithSameKeyCreateOneTransfer() throws Exception {
        for (int round = 0; round < 5; round++) {
            String key = "parallel-" + UUID.randomUUID();
            CountDownLatch startTogether = new CountDownLatch(1);
            List<Future<ResponseEntity<String>>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                calls.add(pool.submit(() -> {
                    startTogether.await();
                    return api.post("/transfers", transferJson(source, target, "25.00"), key);
                }));
            }
            startTogether.countDown();

            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> call : calls) {
                responses.add(call.get(30, TimeUnit.SECONDS));
            }

            assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
            assertThat(responses.stream().map(r -> json(r).get("id").asText()).distinct()).hasSize(1);
            assertThat(responses.stream().filter(r -> !r.getHeaders().containsKey(TransferController.REPLAYED)))
                    .as("exactly one response reports a new transfer").hasSize(1);
            assertThat(countTransfersWithKey(key)).isEqualTo(1);
        }
    }

    /**
     * Forces the race deterministically: another transaction has inserted the key but not committed.
     * The request must wait, then return that transaction's transfer as a replay.
     */
    @Test
    void requestWaitsForUncommittedSameKeyAndReplaysItAfterCommit() throws Exception {
        String key = "blocked-commit-" + UUID.randomUUID();
        UUID competitorId = UUID.randomUUID();

        try (Connection competitor = dataSource.getConnection()) {
            competitor.setAutoCommit(false);
            insertTransfer(competitor, competitorId, key);

            CompletableFuture<ResponseEntity<String>> request = CompletableFuture.supplyAsync(
                    () -> api.post("/transfers", transferJson(source, target, "40.00"), key), pool);
            awaitBlockedOnLock();
            assertThat(request).isNotDone();

            competitor.commit();

            ResponseEntity<String> response = request.get(30, TimeUnit.SECONDS);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(json(response).get("id").asText()).isEqualTo(competitorId.toString());
            assertThat(response.getHeaders().getFirst(TransferController.REPLAYED)).isEqualTo("true");
        }
        assertThat(countTransfersWithKey(key)).isEqualTo(1);
    }

    /** If the competing transaction rolls back, the waiting request must insert its own transfer. */
    @Test
    void requestWaitsForUncommittedSameKeyAndInsertsAfterRollback() throws Exception {
        String key = "blocked-rollback-" + UUID.randomUUID();
        UUID competitorId = UUID.randomUUID();

        try (Connection competitor = dataSource.getConnection()) {
            competitor.setAutoCommit(false);
            insertTransfer(competitor, competitorId, key);

            CompletableFuture<ResponseEntity<String>> request = CompletableFuture.supplyAsync(
                    () -> api.post("/transfers", transferJson(source, target, "40.00"), key), pool);
            awaitBlockedOnLock();

            competitor.rollback();

            ResponseEntity<String> response = request.get(30, TimeUnit.SECONDS);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(json(response).get("id").asText()).isNotEqualTo(competitorId.toString());
            assertThat(response.getHeaders().containsKey(TransferController.REPLAYED)).isFalse();
        }
        assertThat(countTransfersWithKey(key)).isEqualTo(1);
    }

    private void insertTransfer(Connection connection, UUID id, String key) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO transfers (id, source_account_id, target_account_id, amount, currency, "
                        + "idempotency_key, created_at) VALUES (?, ?, ?, 40.00, 'BDT', ?, now())")) {
            insert.setObject(1, id);
            insert.setObject(2, source);
            insert.setObject(3, target);
            insert.setString(4, key);
            insert.executeUpdate();
        }
    }

    /** Waits until some session in this database is waiting for a lock, i.e. our request is blocked. */
    private void awaitBlockedOnLock() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Request never blocked on the competing transaction's lock");
    }

    private int countTransfersWithKey(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM transfers WHERE idempotency_key = ?", Integer.class, key);
    }
}
