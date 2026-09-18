package com.flightcontrol.service;

import com.flightcontrol.domain.Flight;
import com.flightcontrol.dto.FlightRequest;
import com.flightcontrol.dto.FlightResponse;
import com.flightcontrol.exception.BusinessRuleViolationException;
import com.flightcontrol.exception.DuplicateFlightNumberException;
import com.flightcontrol.repository.FlightRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class FlightService {

    private final FlightRepository flightRepository;
    private final Clock clock;

    public FlightService(FlightRepository flightRepository, Clock clock) {
        this.flightRepository = flightRepository;
        this.clock = clock;
    }

    @Transactional
    public FlightResponse create(FlightRequest request) {
        FlightRequest details = normalize(request);
        validateDataRules(details);
        if (flightRepository.existsByFlightNumberIgnoreCase(details.flightNumber())) {
            throw new DuplicateFlightNumberException(details.flightNumber());
        }
        Flight flight = new Flight(details.flightNumber(), details.origin(), details.destination(),
                details.departureTime(), details.arrivalTime());
        // Flush so a unique-constraint race surfaces here, translated, not at commit.
        return FlightResponse.from(flightRepository.saveAndFlush(flight));
    }

    private static FlightRequest normalize(FlightRequest request) {
        return new FlightRequest(
                request.flightNumber().toUpperCase(Locale.ROOT),
                request.origin().toUpperCase(Locale.ROOT),
                request.destination().toUpperCase(Locale.ROOT),
                request.departureTime(),
                request.arrivalTime());
    }

    private void validateDataRules(FlightRequest details) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        if (!details.departureTime().isAfter(LocalDateTime.now(clock))) {
            fieldErrors.put("departureTime", "must be in the future");
        }
        if (!details.arrivalTime().isAfter(details.departureTime())) {
            fieldErrors.put("arrivalTime", "must be after departure time");
        }
        if (details.origin().equals(details.destination())) {
            fieldErrors.put("destination", "must differ from origin");
        }
        if (!fieldErrors.isEmpty()) {
            throw new BusinessRuleViolationException(fieldErrors);
        }
    }
}
