package com.flightcontrol.dto;

import com.flightcontrol.domain.Flight;
import com.flightcontrol.domain.FlightStatus;

import java.time.LocalDateTime;

public record FlightResponse(
        Long id,
        String flightNumber,
        String origin,
        String destination,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        FlightStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static FlightResponse from(Flight flight) {
        return new FlightResponse(
                flight.getId(),
                flight.getFlightNumber(),
                flight.getOrigin(),
                flight.getDestination(),
                flight.getDepartureTime(),
                flight.getArrivalTime(),
                flight.getStatus(),
                flight.getCreatedAt(),
                flight.getUpdatedAt());
    }
}
