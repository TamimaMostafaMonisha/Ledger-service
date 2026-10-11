-- M1: accounts and recorded transfer instructions. No balances or journal yet (M2).

CREATE TABLE accounts (
    id         uuid         PRIMARY KEY,
    label      varchar(100) NOT NULL,
    currency   char(3)      NOT NULL,
    created_at timestamptz  NOT NULL,
    CONSTRAINT accounts_label_not_blank CHECK (btrim(label) <> ''),
    CONSTRAINT accounts_currency_bdt    CHECK (currency = 'BDT')
);

CREATE TABLE transfers (
    id                uuid          PRIMARY KEY,
    source_account_id uuid          NOT NULL REFERENCES accounts (id),
    target_account_id uuid          NOT NULL REFERENCES accounts (id),
    amount            numeric(19,2) NOT NULL,
    currency          char(3)       NOT NULL,
    idempotency_key   varchar(128)  NOT NULL,
    created_at        timestamptz   NOT NULL,
    CONSTRAINT transfers_idempotency_key_uk UNIQUE (idempotency_key),
    CONSTRAINT transfers_amount_positive    CHECK (amount > 0),
    CONSTRAINT transfers_currency_bdt       CHECK (currency = 'BDT'),
    CONSTRAINT transfers_key_not_blank      CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT transfers_distinct_accounts  CHECK (source_account_id <> target_account_id)
);

-- PostgreSQL does not index foreign keys automatically; M2 will look transfers up by account.
CREATE INDEX transfers_source_account_idx ON transfers (source_account_id);
CREATE INDEX transfers_target_account_idx ON transfers (target_account_id);
