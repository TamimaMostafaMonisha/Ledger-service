package dev.portfolio.ledger.transfer;

import static dev.portfolio.ledger.support.LedgerClient.assertError;
import static dev.portfolio.ledger.support.LedgerClient.json;
import static dev.portfolio.ledger.support.LedgerClient.transferJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

import dev.portfolio.ledger.support.IntegrationTest;

class TransferApiTest extends IntegrationTest {

    private UUID source;
    private UUID target;

    @BeforeEach
    void createAccounts() {
        source = api.createAccount("Wallet A");
        target = api.createAccount("Wallet B");
    }

    // --- 3. create and read ---

    @Test
    void recordsTransferWithExactAmountAndReadsItBack() {
        String key = newKey();

        ResponseEntity<String> created = api.post("/transfers", transferJson(source, target, "1234.50"), key);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().containsKey(TransferController.REPLAYED)).isFalse();
        JsonNode body = json(created);
        String id = body.get("id").asText();
        assertThat(body.get("sourceAccountId").asText()).isEqualTo(source.toString());
        assertThat(body.get("targetAccountId").asText()).isEqualTo(target.toString());
        assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("1234.50");
        assertThat(created.getBody()).contains("\"amount\":1234.50");
        assertThat(body.get("currency").asText()).isEqualTo("BDT");
        assertThat(body.get("idempotencyKey").asText()).isEqualTo(key);
        assertThat(created.getHeaders().getLocation()).hasPath("/transfers/" + id);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM transfers WHERE id = ?::uuid", id);
        assertThat(row.get("amount")).isEqualTo(new BigDecimal("1234.50"));
        assertThat(row.get("source_account_id").toString()).isEqualTo(source.toString());
        assertThat(row.get("target_account_id").toString()).isEqualTo(target.toString());

        ResponseEntity<String> read = api.get(created.getHeaders().getLocation().getPath());
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(read)).isEqualTo(body);
    }

    @Test
    void returns404ForUnknownTransfer() {
        assertError(api.get("/transfers/" + UUID.randomUUID()), HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND");
    }

    @Test
    void returns400ForTransferIdThatIsNotUuid() {
        assertError(api.get("/transfers/123"), HttpStatus.BAD_REQUEST, "INVALID_PARAMETER");
    }

    // --- 4. invalid requests store nothing and do not use up the key ---

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1.00", "10.001", "0.009", "100000000000000000", "1e20", "null"})
    void rejectsInvalidAmount(String amount) {
        assertRejectedWithoutReservingKey(transferJson(source, target, amount), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
    }

    @Test
    void acceptsLargestAmountThatFitsTheColumn() {
        ResponseEntity<String> created = api.post("/transfers",
                transferJson(source, target, "99999999999999999.99"), newKey());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void rejectsAmountThatIsNotANumber() {
        assertRejectedWithoutReservingKey(transferJson(source, target, "\"ten\""), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
    }

    @Test
    void rejectsAccountIdThatIsNotUuid() {
        String json = "{\"sourceAccountId\":\"not-a-uuid\",\"targetAccountId\":\"" + target + "\",\"amount\":5}";
        assertRejectedWithoutReservingKey(json, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }

    @Test
    void rejectsMissingAccountIds() {
        assertRejectedWithoutReservingKey("{\"amount\":5}", HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void rejectsUnknownAccount() {
        assertRejectedWithoutReservingKey(transferJson(source, UUID.randomUUID(), "5.00"), HttpStatus.NOT_FOUND,
                "ACCOUNT_NOT_FOUND");
    }

    @Test
    void rejectsTransferToSameAccount() {
        assertRejectedWithoutReservingKey(transferJson(source, source, "5.00"), HttpStatus.BAD_REQUEST,
                "SELF_TRANSFER");
    }

    @Test
    void rejectsMissingBlankOrTooLongKey() {
        String json = transferJson(source, target, "5.00");
        int before = countRows("transfers");

        assertError(api.post("/transfers", json, null), HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        assertError(api.post("/transfers", json, "   "), HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        assertError(api.post("/transfers", json, "k".repeat(129)), HttpStatus.BAD_REQUEST,
                "INVALID_IDEMPOTENCY_KEY");
        assertThat(countRows("transfers")).isEqualTo(before);
    }

    @Test
    void acceptsKeyOfExactly128Characters() {
        String key = "k".repeat(127) + UUID.randomUUID().toString().charAt(0);

        assertThat(api.post("/transfers", transferJson(source, target, "5.00"), key).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    // --- 5. same key, same request: replay ---

    /** Stored as 10.00, so retries written as 10.0 and 10 must match by numeric value, not by scale. */
    @ParameterizedTest
    @ValueSource(strings = {"10.00", "10.0", "10", "1E+1"})
    void retryWithSameKeyReplaysOriginalResponse(String retryAmount) {
        String key = newKey();
        ResponseEntity<String> first = api.post("/transfers", transferJson(source, target, "10.00"), key);

        ResponseEntity<String> retry = api.post("/transfers", transferJson(source, target, retryAmount), key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(retry.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(retry.getHeaders().getFirst(TransferController.REPLAYED)).isEqualTo("true");
        assertThat(countTransfersWithKey(key)).isEqualTo(1);
    }

    @Test
    void keysAreCaseSensitive() {
        String key = "Order-" + UUID.randomUUID();
        ResponseEntity<String> upper = api.post("/transfers", transferJson(source, target, "1.00"), key);
        ResponseEntity<String> lower = api.post("/transfers", transferJson(source, target, "1.00"), key.toLowerCase());

        assertThat(json(upper).get("id")).isNotEqualTo(json(lower).get("id"));
    }

    // --- 6. same key, different request: 409 ---

    @Test
    void sameKeyWithDifferentRequestIsRejectedAndOriginalKept() {
        String key = newKey();
        UUID other = api.createAccount("Wallet C");
        ResponseEntity<String> first = api.post("/transfers", transferJson(source, target, "10.00"), key);
        Map<String, Object> original = rowForKey(key);

        assertError(api.post("/transfers", transferJson(source, target, "10.01"), key),
                HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
        assertError(api.post("/transfers", transferJson(source, other, "10.00"), key),
                HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
        assertError(api.post("/transfers", transferJson(target, source, "10.00"), key),
                HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");

        assertThat(rowForKey(key)).isEqualTo(original);
        assertThat(api.get(first.getHeaders().getLocation().getPath()).getBody()).isEqualTo(first.getBody());
    }

    private void assertRejectedWithoutReservingKey(String json, HttpStatus status, String code) {
        String key = newKey();
        int before = countRows("transfers");

        assertError(api.post("/transfers", json, key), status, code);

        assertThat(countRows("transfers")).isEqualTo(before);
        assertThat(api.post("/transfers", transferJson(source, target, "1.00"), key).getStatusCode())
                .as("key is still free after a rejected request").isEqualTo(HttpStatus.CREATED);
    }

    private int countTransfersWithKey(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM transfers WHERE idempotency_key = ?", Integer.class, key);
    }

    private Map<String, Object> rowForKey(String key) {
        return jdbc.queryForMap("SELECT * FROM transfers WHERE idempotency_key = ?", key);
    }

    private static String newKey() {
        return "test-" + UUID.randomUUID();
    }
}
