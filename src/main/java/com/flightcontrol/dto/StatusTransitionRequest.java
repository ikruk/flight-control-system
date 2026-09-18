package com.flightcontrol.dto;

import com.flightcontrol.domain.FlightStatus;
import jakarta.validation.constraints.NotNull;

public record StatusTransitionRequest(@NotNull(message = "must not be null") FlightStatus status) {
}
