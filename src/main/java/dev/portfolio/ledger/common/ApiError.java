package dev.portfolio.ledger.common;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Error body for every non-2xx response. {@code code} is stable and meant for programs;
 * {@code message} is for humans and may change.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, String message, List<FieldViolation> fieldErrors) {

    public ApiError(String code, String message) {
        this(code, message, List.of());
    }

    public record FieldViolation(String field, String message) {
    }
}
