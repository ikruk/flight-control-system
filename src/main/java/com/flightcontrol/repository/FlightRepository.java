package com.flightcontrol.repository;

import com.flightcontrol.domain.Flight;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlightRepository extends JpaRepository<Flight, Long> {

    boolean existsByFlightNumberIgnoreCase(String flightNumber);

    boolean existsByFlightNumberIgnoreCaseAndIdNot(String flightNumber, Long id);
}
