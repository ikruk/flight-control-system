package com.flightcontrol.exception;

public class FlightNotFoundException extends RuntimeException {

    public FlightNotFoundException(Long id) {
        super("Flight %d not found".formatted(id));
    }
}
