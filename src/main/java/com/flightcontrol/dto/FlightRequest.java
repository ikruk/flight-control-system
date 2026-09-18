package com.flightcontrol.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;

/**
 * Field-shape rules only. Cross-field and uniqueness rules live in FlightService.
 * NotNull + Pattern gives exactly one message per field: Pattern passes on null and fails on "".
 */
public record FlightRequest(

        @NotNull(message = "is required")
        @Pattern(regexp = "^[A-Za-z0-9]{2}\\d{1,4}[A-Za-z]?$",
                message = "must be a 2-character airline code followed by 1-4 digits and an optional letter, e.g. BA117")
        String flightNumber,

        @NotNull(message = "is required")
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter airport code, e.g. LHR")
        String origin,

        @NotNull(message = "is required")
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter airport code, e.g. LHR")
        String destination,

        @NotNull(message = "is required")
        LocalDateTime departureTime,

        @NotNull(message = "is required")
        LocalDateTime arrivalTime
) {
}
