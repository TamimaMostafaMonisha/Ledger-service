package dev.portfolio.ledger.transfer;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import dev.portfolio.ledger.common.ApiException;
import dev.portfolio.ledger.transfer.TransferDtos.CreateTransferRequest;
import dev.portfolio.ledger.transfer.TransferDtos.TransferResponse;
import dev.portfolio.ledger.transfer.TransferDtos.TransferResult;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/transfers")
class TransferController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";
    private static final int MAX_KEY_LENGTH = 128;

    private final TransferService service;

    TransferController(TransferService service) {
        this.service = service;
    }

    /** A replay returns the original 201, body and Location, plus a header saying it is a replay. */
    @PostMapping
    ResponseEntity<TransferResponse> create(
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {
        TransferResult result = service.record(requireValidKey(idempotencyKey), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(result.transfer().id()).toUri();
        ResponseEntity.BodyBuilder response = ResponseEntity.created(location);
        if (!result.created()) {
            response.header(REPLAYED, "true");
        }
        return response.body(result.transfer());
    }

    @GetMapping("/{id}")
    TransferResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    private static String requireValidKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required, must not be blank and must be at most "
                            + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }
}
