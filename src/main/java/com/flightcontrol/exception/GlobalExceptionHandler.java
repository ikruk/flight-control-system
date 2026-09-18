package com.flightcontrol.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link BusinessRuleViolationException} (carries a {@code fieldErrors} map) and
 * {@link DuplicateFlightNumberException} (the 409 duplicate-flight-number case) have no
 * handlers here deliberately: no endpoint can raise them yet, since nothing calls
 * {@code FlightService.create} over HTTP. Handlers for a currently unreachable path could not
 * be tested. The story that adds a create endpoint must add handlers for both exceptions, along
 * with tests for them.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(FlightNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleFlightNotFound(FlightNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of(HttpStatus.NOT_FOUND, "FlightNotFound", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStatusTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalTransition(IllegalStatusTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(HttpStatus.CONFLICT, "IllegalStatusTransition", ex.getMessage()));
    }

    /**
     * A path variable or request parameter that will not convert to its declared type - for
     * example a non-numeric flight id. Keyed by parameter name so clients read fieldErrors the
     * same way they do for body validation failures.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        fieldErrors.put(ex.getName(), "must be %s".formatted(expectedTypeOf(ex)));
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.validationFailed("Request parameter is not valid", fieldErrors));
    }

    private static String expectedTypeOf(MethodArgumentTypeMismatchException ex) {
        Class<?> required = ex.getRequiredType();
        if (required == null) {
            return "a valid value";
        }
        if (Number.class.isAssignableFrom(required)) {
            return "a number";
        }
        if (required.isEnum()) {
            return "one of: " + Arrays.stream(required.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
        }
        return "a valid %s".formatted(required.getSimpleName());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.validationFailed("Request validation failed", fieldErrors));
    }

    /**
     * An unknown enum name never reaches Bean Validation - Jackson fails to bind it first.
     * Unwrapping it here keeps malformed input on the same JSON shape as every other error.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        if (ex.getCause() instanceof InvalidFormatException cause && cause.getTargetType().isEnum()) {
            String field = cause.getPath().isEmpty()
                    ? "body"
                    : cause.getPath().get(cause.getPath().size() - 1).getFieldName();
            String allowed = Arrays.stream(cause.getTargetType().getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            fieldErrors.put(field, "must be one of: " + allowed);
        }
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.validationFailed("Request body is not readable",
                        fieldErrors.isEmpty() ? null : fieldErrors));
    }
}
