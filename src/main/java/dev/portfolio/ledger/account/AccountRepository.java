package dev.portfolio.ledger.account;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AccountRepository extends JpaRepository<Account, UUID> {
}
