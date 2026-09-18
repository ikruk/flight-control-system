package com.flightcontrol.exception;

import com.flightcontrol.domain.Flight;
import com.flightcontrol.domain.FlightStatus;

import java.util.stream.Collectors;

public class IllegalStatusTransitionException extends RuntimeException {

    public IllegalStatusTransitionException(Flight flight) {
        super(describe(flight));
    }

    private static String describe(Flight flight) {
        FlightStatus current = flight.getStatus();
        if (current.isTerminal()) {
            return "Flight %s is %s; %s is terminal, no transitions are allowed"
                    .formatted(flight.getFlightNumber(), current, current);
        }
        return "Flight %s is %s; allowed transitions: %s".formatted(
                flight.getFlightNumber(),
                current,
                current.allowedNextStatuses().stream()
                        .map(Enum::name)
                        .collect(Collectors.joining(", ")));
    }
}
