package com.flightcontrol.repository;

import com.flightcontrol.domain.Flight;
import com.flightcontrol.domain.FlightStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the contents of data.sql itself. Other tests must not rely on seeded rows.
 */
@DataJpaTest
class SeedDataTest {

    private final FlightRepository flightRepository;

    @Autowired
    SeedDataTest(FlightRepository flightRepository) {
        this.flightRepository = flightRepository;
    }

    @Test
    void seedsAtLeastOneFlightInEveryStatus() {
        List<FlightStatus> seededStatuses = flightRepository.findAll().stream()
                .map(Flight::getStatus)
                .distinct()
                .toList();

        assertThat(seededStatuses).containsExactlyInAnyOrder(FlightStatus.values());
    }

    @Test
    void seededFlightsHaveCoherentRoutesAndTimes() {
        assertThat(flightRepository.findAll()).allSatisfy(flight -> {
            assertThat(flight.getOrigin()).isNotEqualTo(flight.getDestination());
            assertThat(flight.getArrivalTime()).isAfter(flight.getDepartureTime());
        });
    }

    @Test
    void seededTimestampsAreRelativeToNow() {
        LocalDateTime now = LocalDateTime.now();

        assertThat(flightRepository.findAll()).allSatisfy(flight -> {
            switch (flight.getStatus()) {
                case SCHEDULED, DELAYED -> assertThat(flight.getDepartureTime()).isAfter(now);
                case DEPARTED, IN_AIR -> {
                    assertThat(flight.getDepartureTime()).isBefore(now);
                    assertThat(flight.getArrivalTime()).isAfter(now);
                }
                case LANDED -> assertThat(flight.getArrivalTime()).isBefore(now);
                case CANCELLED -> { }
            }
        });
    }
}
