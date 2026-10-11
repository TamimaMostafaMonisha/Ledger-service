package dev.portfolio.ledger.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface TransferRepository extends JpaRepository<Transfer, UUID> {

    /**
     * Inserts the transfer unless a row with the same idempotency key exists. Returns 1 if inserted, 0 if not.
     *
     * <p>If another transaction has inserted the same key but not committed yet, PostgreSQL makes this
     * statement wait for it. After that transaction commits we get 0; after it rolls back we insert. The
     * statement never fails on a duplicate key, so our transaction stays usable for the read that follows.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO transfers (id, source_account_id, target_account_id, amount, currency, idempotency_key, created_at)
            VALUES (:id, :sourceAccountId, :targetAccountId, :amount, :currency, :idempotencyKey, :createdAt)
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int insertIfKeyAbsent(UUID id, UUID sourceAccountId, UUID targetAccountId, BigDecimal amount,
            String currency, String idempotencyKey, Instant createdAt);

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);
}
