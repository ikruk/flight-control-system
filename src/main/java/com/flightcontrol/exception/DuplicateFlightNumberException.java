package com.flightcontrol.exception;

public class DuplicateFlightNumberException extends RuntimeException {

    public DuplicateFlightNumberException(String flightNumber) {
        super("Flight number " + flightNumber + " already exists");
    }
}
