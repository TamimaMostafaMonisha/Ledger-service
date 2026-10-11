package dev.portfolio.ledger.account;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response shapes for /accounts. Kept separate from the entity on purpose. */
final class AccountDtos {

    private AccountDtos() {
    }

    record CreateAccountRequest(@NotBlank @Size(max = 100) String label) {
    }

    record AccountResponse(UUID id, String label, String currency, Instant createdAt) {

        static AccountResponse from(Account account) {
            return new AccountResponse(account.getId(), account.getLabel(), account.getCurrency(),
                    account.getCreatedAt());
        }
    }
}
