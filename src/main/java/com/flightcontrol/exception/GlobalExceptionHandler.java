package com.flightcontrol.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

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
