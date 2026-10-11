package dev.portfolio.ledger.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import dev.portfolio.ledger.common.Money;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Request and response shapes for /transfers. */
final class TransferDtos {

    private TransferDtos() {
    }

    /**
     * {@code @Digits} ignores trailing zeros, so 10.0 and 10.000 are accepted but 10.001 is not,
     * and anything that would not fit numeric(19,2) is rejected before it reaches the database.
     */
    record CreateTransferRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID targetAccountId,
            @NotNull @Positive @Digits(integer = Money.INTEGER_DIGITS, fraction = Money.SCALE) BigDecimal amount) {
    }

    record TransferResponse(UUID id, UUID sourceAccountId, UUID targetAccountId, BigDecimal amount,
            String currency, String idempotencyKey, Instant createdAt) {

        static TransferResponse from(Transfer transfer) {
            return new TransferResponse(transfer.getId(), transfer.getSourceAccountId(),
                    transfer.getTargetAccountId(), transfer.getAmount(), transfer.getCurrency(),
                    transfer.getIdempotencyKey(), transfer.getCreatedAt());
        }
    }

    /** {@code created} is false when the response replays an earlier request with the same key. */
    record TransferResult(TransferResponse transfer, boolean created) {
    }
}
