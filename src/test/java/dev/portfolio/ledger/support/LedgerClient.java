package dev.portfolio.ledger.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Real HTTP calls to a running ledger-service. Bodies stay raw strings so tests see exact JSON. */
public final class LedgerClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TestRestTemplate rest;

    public LedgerClient(TestRestTemplate rest) {
        this.rest = rest;
    }

    public ResponseEntity<String> post(String path, String json) {
        return post(path, json, null);
    }

    public ResponseEntity<String> post(String path, String json, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    public ResponseEntity<String> get(String path) {
        return rest.getForEntity(path, String.class);
    }

    public UUID createAccount(String label) {
        ResponseEntity<String> response = post("/accounts", "{\"label\":\"" + label + "\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(json(response).get("id").asText());
    }

    public static String transferJson(UUID source, UUID target, String amount) {
        return "{\"sourceAccountId\":\"" + source + "\",\"targetAccountId\":\"" + target
                + "\",\"amount\":" + amount + "}";
    }

    public static JsonNode json(ResponseEntity<String> response) {
        try {
            return JSON.readTree(response.getBody());
        } catch (JsonProcessingException e) {
            throw new AssertionError("Response is not JSON: " + response.getBody(), e);
        }
    }

    /** Asserts the status and stable error code, and that no Java internals leak to the client. */
    public static void assertError(ResponseEntity<String> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(json(response).get("code").asText()).isEqualTo(code);
        assertThat(response.getBody()).doesNotContain("Exception", "trace", "at dev.portfolio");
    }
}
