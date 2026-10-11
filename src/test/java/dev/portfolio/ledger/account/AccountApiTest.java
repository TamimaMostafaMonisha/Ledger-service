package dev.portfolio.ledger.account;

import static dev.portfolio.ledger.support.LedgerClient.assertError;
import static dev.portfolio.ledger.support.LedgerClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

import dev.portfolio.ledger.support.IntegrationTest;

class AccountApiTest extends IntegrationTest {

    @Test
    void createsAccountAndReadsItBack() {
        ResponseEntity<String> created = api.post("/accounts", "{\"label\":\"Household savings\"}");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json(created);
        String id = body.get("id").asText();
        assertThat(body.get("label").asText()).isEqualTo("Household savings");
        assertThat(body.get("currency").asText()).isEqualTo("BDT");
        assertThat(body.get("createdAt").asText()).isNotBlank();
        assertThat(created.getHeaders().getLocation()).hasPath("/accounts/" + id);

        ResponseEntity<String> read = api.get(created.getHeaders().getLocation().getPath());
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(read)).isEqualTo(body);
    }

    @Test
    void acceptsLabelOfExactlyOneHundredCharacters() {
        ResponseEntity<String> created = api.post("/accounts", "{\"label\":\"" + "a".repeat(100) + "\"}");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    static Stream<String> invalidLabels() {
        return Stream.of("{}", "{\"label\":null}", "{\"label\":\"\"}", "{\"label\":\"   \"}",
                "{\"label\":\"" + "a".repeat(101) + "\"}");
    }

    @ParameterizedTest
    @MethodSource("invalidLabels")
    void rejectsInvalidLabelAndStoresNothing(String json) {
        int before = countRows("accounts");

        assertError(api.post("/accounts", json), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(countRows("accounts")).isEqualTo(before);
    }

    @Test
    void rejectsBodyThatIsNotJson() {
        assertError(api.post("/accounts", "label=x"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }

    @Test
    void returns404ForUnknownAccount() {
        assertError(api.get("/accounts/" + UUID.randomUUID()), HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND");
    }

    @Test
    void returns400ForIdThatIsNotUuid() {
        assertError(api.get("/accounts/not-a-uuid"), HttpStatus.BAD_REQUEST, "INVALID_PARAMETER");
    }
}
