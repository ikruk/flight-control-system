package com.flightcontrol.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        Map<String, String> fieldErrors) {

    public static ApiErrorResponse of(HttpStatus status, String error, String message) {
        return new ApiErrorResponse(LocalDateTime.now(), status.value(), error, message, null);
    }

    public static ApiErrorResponse validationFailed(String message, Map<String, String> fieldErrors) {
        return new ApiErrorResponse(LocalDateTime.now(), HttpStatus.BAD_REQUEST.value(),
                "ValidationFailed", message, fieldErrors);
    }
}
