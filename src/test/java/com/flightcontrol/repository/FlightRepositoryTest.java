package com.flightcontrol.repository;

import com.flightcontrol.domain.Flight;
import com.flightcontrol.domain.FlightStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class FlightRepositoryTest {

    private final FlightRepository flightRepository;
    private final TestEntityManager entityManager;

    @Autowired
    FlightRepositoryTest(FlightRepository flightRepository, TestEntityManager entityManager) {
        this.flightRepository = flightRepository;
        this.entityManager = entityManager;
    }

    @Test
    void persistsAndReadsBackAllFlightAttributes() {
        LocalDateTime departure = LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime arrival = departure.plusHours(3);

        Long id = flightRepository.saveAndFlush(
                new Flight("TEST100", "JFK", "LHR", departure, arrival)).getId();
        entityManager.clear();

        Flight found = flightRepository.findById(id).orElseThrow();
        assertThat(found.getFlightNumber()).isEqualTo("TEST100");
        assertThat(found.getOrigin()).isEqualTo("JFK");
        assertThat(found.getDestination()).isEqualTo("LHR");
        assertThat(found.getDepartureTime()).isEqualTo(departure);
        assertThat(found.getArrivalTime()).isEqualTo(arrival);
    }

    @Test
    void newFlightIsScheduledByDefault() {
        Long id = flightRepository.saveAndFlush(aFlight("TEST200")).getId();
        entityManager.clear();

        assertThat(flightRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(FlightStatus.SCHEDULED);
    }

    @Test
    void setsCreatedAtAndUpdatedAtOnInsert() {
        LocalDateTime before = LocalDateTime.now();

        Long id = flightRepository.saveAndFlush(aFlight("TEST300")).getId();
        entityManager.clear();

        Flight found = flightRepository.findById(id).orElseThrow();
        assertThat(found.getCreatedAt()).isNotNull().isAfterOrEqualTo(before.truncatedTo(ChronoUnit.MICROS));
        assertThat(found.getUpdatedAt()).isEqualTo(found.getCreatedAt());
    }

    @Test
    void refreshesUpdatedAtButKeepsCreatedAtOnModification() {
        Flight flight = flightRepository.saveAndFlush(aFlight("TEST400"));
        LocalDateTime createdAt = flight.getCreatedAt();
        LocalDateTime firstUpdatedAt = flight.getUpdatedAt();

        flight.setOrigin("CDG");
        flightRepository.saveAndFlush(flight);
        entityManager.clear();

        Flight found = flightRepository.findById(flight.getId()).orElseThrow();
        assertThat(found.getCreatedAt()).isEqualTo(createdAt);
        assertThat(found.getUpdatedAt()).isAfter(firstUpdatedAt);
    }

    @Test
    void findsExistingFlightNumberRegardlessOfCase() {
        flightRepository.saveAndFlush(aFlight("CASE100"));

        assertThat(flightRepository.existsByFlightNumberIgnoreCase("case100")).isTrue();
        assertThat(flightRepository.existsByFlightNumberIgnoreCase("CASE100")).isTrue();
        assertThat(flightRepository.existsByFlightNumberIgnoreCase("CASE999")).isFalse();
    }

    @Test
    void ignoresTheFlightItselfWhenLookingForAnotherWithTheSameNumber() {
        Flight own = flightRepository.saveAndFlush(aFlight("SELF100"));
        Flight other = flightRepository.saveAndFlush(aFlight("SELF200"));

        assertThat(flightRepository.existsByFlightNumberIgnoreCaseAndIdNot("self100", own.getId())).isFalse();
        assertThat(flightRepository.existsByFlightNumberIgnoreCaseAndIdNot("self100", other.getId())).isTrue();
    }

    private static Flight aFlight(String flightNumber) {
        LocalDateTime departure = LocalDateTime.now().plusDays(1);
        return new Flight(flightNumber, "JFK", "LHR", departure, departure.plusHours(3));
    }
}
