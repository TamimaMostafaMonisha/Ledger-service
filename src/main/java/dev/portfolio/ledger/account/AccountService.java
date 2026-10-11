package dev.portfolio.ledger.account;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.portfolio.ledger.account.AccountDtos.AccountResponse;
import dev.portfolio.ledger.common.ApiException;
import dev.portfolio.ledger.common.Money;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final Clock clock;

    AccountService(AccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    AccountResponse create(String label) {
        // PostgreSQL stores microseconds; truncating here makes the POST and a later GET identical.
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        return AccountResponse.from(accounts.save(new Account(label, Money.CURRENCY, now)));
    }

    @Transactional(readOnly = true)
    AccountResponse get(UUID id) {
        return accounts.findById(id).map(AccountResponse::from).orElseThrow(() -> notFound(id));
    }

    /** Used by transfers: fails with 404 if the account does not exist. */
    @Transactional(readOnly = true)
    public void requireExists(UUID id) {
        if (!accounts.existsById(id)) {
            throw notFound(id);
        }
    }

    private static ApiException notFound(UUID id) {
        return ApiException.notFound("ACCOUNT_NOT_FOUND", "Account " + id + " does not exist");
    }
}
