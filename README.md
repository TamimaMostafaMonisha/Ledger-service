# ledger-service

A payments ledger API in Java 21 and Spring Boot 3. It focuses on money correctness: exact decimal
amounts, safe retries (`Idempotency-Key`), and rules enforced by the database as well as the code.

**Status: milestone M1.** Accounts can be created and read. Transfer *instructions* can be recorded
and read, and recording is idempotent. No money moves yet: balances, a double-entry journal and
locking come in M2.

## Architecture

```
 client ──HTTP/JSON──▶ Controller ──▶ Service (@Transactional) ──▶ Repository ──▶ PostgreSQL
                        │  DTOs,        │  business rules,           │  JPA +        │  Flyway schema,
                        │  validation   │  idempotency               │  native SQL   │  constraints
                        └── ApiExceptionHandler: every error → {code, message}
```

One Maven module, package `dev.portfolio.ledger`:

| Package | Contents |
|---|---|
| `account` | `AccountController`, `AccountDtos`, `AccountService`, `Account` entity, repository |
| `transfer` | `TransferController`, `TransferDtos`, `TransferService`, `Transfer` entity, repository |
| `common` | `ApiError`, `ApiException`, `ApiExceptionHandler`, `Money` (currency/scale rules) |

Schema: [`V1__accounts_and_transfers.sql`](src/main/resources/db/migration/V1__accounts_and_transfers.sql).

## Run it

Requirements: Docker. For development without Docker: JDK 21 and a PostgreSQL server.

```bash
docker compose up --build        # API on http://localhost:8080, PostgreSQL in a container
docker compose down -v           # stop and delete the data volume
```

### Try it

```bash
# Create two accounts
curl -s -X POST localhost:8080/accounts -H 'Content-Type: application/json' -d '{"label":"Alice wallet"}'
curl -s -X POST localhost:8080/accounts -H 'Content-Type: application/json' -d '{"label":"Bob wallet"}'

# Record a transfer (use the two ids from above)
curl -i -X POST localhost:8080/transfers \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: order-1001' \
  -d '{"sourceAccountId":"<alice-id>","targetAccountId":"<bob-id>","amount":150.50}'
# → 201 Created, Location: /transfers/<id>

# Retry with the same key (e.g. after a timeout): same 201, same body, same Location,
# plus header "Idempotent-Replayed: true". No second transfer is recorded.

# Same key, different amount → 409 {"code":"IDEMPOTENCY_KEY_REUSED", ...}

curl -s localhost:8080/transfers/<id>
```

## API

| Method and path | Success | Errors |
|---|---|---|
| `POST /accounts` `{"label"}` | 201 + `Location` | 400 `VALIDATION_FAILED`, `MALFORMED_REQUEST` |
| `GET /accounts/{id}` | 200 | 404 `ACCOUNT_NOT_FOUND`, 400 `INVALID_PARAMETER` |
| `POST /transfers` `{"sourceAccountId","targetAccountId","amount"}` + header `Idempotency-Key` | 201 + `Location` | 400 `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_IDEMPOTENCY_KEY`, `SELF_TRANSFER`; 404 `ACCOUNT_NOT_FOUND`; 409 `IDEMPOTENCY_KEY_REUSED` |
| `GET /transfers/{id}` | 200 | 404 `TRANSFER_NOT_FOUND`, 400 `INVALID_PARAMETER` |

Currency is BDT only, with two decimal places. Amounts must be > 0 and fit `numeric(19,2)`.
Errors look like `{"code":"...","message":"...","fieldErrors":[...]}`. `code` is stable; stack traces are never returned.

## Design decisions

**Idempotency lives in the database.** `transfers.idempotency_key` is `UNIQUE`. The service runs
`INSERT … ON CONFLICT (idempotency_key) DO NOTHING`, then reads the row by key. If the insert happened,
it returns 201. If not, it compares the stored request with the new one: same → replay, different → 409.
Because the key is stored with the transfer in one transaction, retries are safe across restarts and
across several app instances. Nothing is kept in memory.

**Why not "check, then insert"?** Two requests can both see "key not found" and both insert. The
second insert would then fail with a unique-key error, which aborts the transaction, so you can no
longer read the winning row in it. With `ON CONFLICT DO NOTHING`, PostgreSQL makes the second request
wait for the first. After the first commits, the insert does nothing and the request reads the winner
(under READ COMMITTED each statement sees the latest committed data). After the first rolls back, the
waiting request inserts its own row. A test proves both cases.

**Invalid requests do not use up a key.** Validation, the self-transfer check and the account-existence
checks all run before the insert, so a rejected request writes nothing and the client can fix the
request and retry with the same key.

**Amounts are `BigDecimal` and `numeric(19,2)`.** Floating point cannot represent 0.10 exactly.
Retries are compared with `compareTo` because `BigDecimal.equals` treats `10.0` and `10.00` as different.
`@Digits(integer=17, fraction=2)` rejects anything the column cannot hold, so the client gets a 400 instead of a database error.

**Flyway owns the schema; Hibernate only validates it.** Versioned SQL migrations are reviewable and
repeatable in every environment. `ddl-auto=update` can silently change production tables.
`ddl-auto=validate` stops the app at startup if entities and tables disagree.

**Constraints in the database too.** `NOT NULL`, `CHECK (amount > 0)`, `CHECK (currency = 'BDT')`,
`CHECK (source <> target)`, foreign keys and the unique key protect the data even if a future code
path forgets a rule.

**DTOs, not entities, on the wire.** Request/response records decouple the API contract from the table
layout and stop clients from setting fields such as `id` or `createdAt`.

**HTTP semantics.** `201 Created` + `Location` tells the client where the new resource lives. A replay
returns the *same* 201 so a retrying client cannot tell a retry from a first success; the extra
`Idempotent-Replayed` header is for debugging. `409 Conflict` means the key was reused for a different
request. `404` means a referenced account does not exist.

**UUID ids, UTC timestamps.** UUIDs can be generated without a database round trip and do not reveal
counts. Timestamps are `timestamptz`, written from an injected UTC `Clock` and truncated to
microseconds (PostgreSQL's precision), so a POST response and a later GET are identical.

## Tests

All tests run against **real PostgreSQL**: no H2, no mocks of the database.

```bash
./mvnw test     # needs Docker: Testcontainers starts postgres:17-alpine
```

No Docker? Point the tests at an existing PostgreSQL server (the user needs `CREATEDB`); each test
run creates fresh databases on it:

```bash
LEDGER_TEST_PG_HOST=localhost LEDGER_TEST_PG_PORT=5432 \
LEDGER_TEST_PG_USER=postgres LEDGER_TEST_PG_PASSWORD=secret ./mvnw test
```

| Test class | What it proves |
|---|---|
| `SchemaAndRestartTest` | A fresh database is migrated and validated; after a restart the migration is not rerun, data survives, and an idempotent retry still replays the original response |
| `AccountApiTest` | Create/read round trip, `Location`, label rules (blank, 100/101 chars), 404, bad id, non-JSON body |
| `TransferApiTest` | Exact amount round trip; invalid amount (0, negative, 3 decimals, overflow), UUID, key, unknown account and self-transfer each return the right error and leave no row/key behind; replay with `10`, `10.0`, `10.00`, `1E+1`; case-sensitive keys; 409 keeps the original row |
| `TransferConcurrencyTest` | 8 simultaneous identical requests give one row and one id. Deterministic race: a request blocked on an uncommitted row with the same key replays it after commit, or inserts its own after rollback |

## Limitations (M1)

- Transfers are recorded, not executed: no balances, journal or overdraft checks yet.
- One currency (BDT). No authentication, pagination or listing endpoints.
- Idempotency keys never expire. A real service would expire them (e.g. after 24 h) and scope them per client.
- No CI, OpenAPI docs, metrics or events yet.

## Roadmap

| Milestone | Scope |
|---|---|
| M2 | Double-entry journal (debits = credits), balances, optimistic vs pessimistic locking, 100-concurrent-transfer test, CI |
| M3 | Transactional outbox → Kafka `transfer.completed`, consumer with retries and dead-letter, OpenAPI |
| M4 | Actuator/Micrometer metrics, rate limiting, k6 load test results |
| M5 | Read-only MCP server so an AI assistant can query balances and transfers safely |
