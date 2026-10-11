package dev.portfolio.ledger.transfer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.portfolio.ledger.account.AccountService;
import dev.portfolio.ledger.common.ApiException;
import dev.portfolio.ledger.common.Money;
import dev.portfolio.ledger.transfer.TransferDtos.CreateTransferRequest;
import dev.portfolio.ledger.transfer.TransferDtos.TransferResponse;
import dev.portfolio.ledger.transfer.TransferDtos.TransferResult;

@Service
class TransferService {

    private final TransferRepository transfers;
    private final AccountService accounts;
    private final Clock clock;

    TransferService(TransferRepository transfers, AccountService accounts, Clock clock) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.clock = clock;
    }

    /**
     * Records a transfer instruction once per idempotency key.
     *
     * <p>Every check that can reject the request runs before the insert, so a rejected request leaves
     * no row and the key stays free. Correctness under concurrency comes from the UNIQUE constraint and
     * {@code ON CONFLICT DO NOTHING}, not from checking first in Java.
     */
    @Transactional
    TransferResult record(String idempotencyKey, CreateTransferRequest request) {
        if (request.sourceAccountId().equals(request.targetAccountId())) {
            throw ApiException.badRequest("SELF_TRANSFER", "Source and target account must be different");
        }
        accounts.requireExists(request.sourceAccountId());
        accounts.requireExists(request.targetAccountId());

        // Exact: validation already guarantees at most two significant decimal places.
        BigDecimal amount = request.amount().setScale(Money.SCALE);
        UUID newId = UUID.randomUUID();
        int inserted = transfers.insertIfKeyAbsent(newId, request.sourceAccountId(), request.targetAccountId(),
                amount, Money.CURRENCY, idempotencyKey, Instant.now(clock).truncatedTo(ChronoUnit.MICROS));

        // Read committed: this new statement sees the row whether we inserted it or a concurrent request did.
        Transfer stored = transfers.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Transfer for key vanished after insert"));
        if (inserted == 1) {
            return new TransferResult(TransferResponse.from(stored), true);
        }
        if (!sameRequest(stored, request)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different transfer");
        }
        return new TransferResult(TransferResponse.from(stored), false);
    }

    @Transactional(readOnly = true)
    TransferResponse get(UUID id) {
        return transfers.findById(id).map(TransferResponse::from)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND", "Transfer " + id + " does not exist"));
    }

    /** compareTo, not equals: BigDecimal.equals treats 10.0 and 10.00 as different. */
    private static boolean sameRequest(Transfer stored, CreateTransferRequest request) {
        return stored.getSourceAccountId().equals(request.sourceAccountId())
                && stored.getTargetAccountId().equals(request.targetAccountId())
                && stored.getAmount().compareTo(request.amount()) == 0;
    }
}
