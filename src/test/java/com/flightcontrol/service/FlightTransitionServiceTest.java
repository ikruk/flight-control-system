package com.flightcontrol.service;

import com.flightcontrol.config.ClockConfig;
import com.flightcontrol.domain.Flight;
import com.flightcontrol.domain.FlightStatus;
import com.flightcontrol.dto.FlightResponse;
import com.flightcontrol.exception.FlightNotFoundException;
import com.flightcontrol.exception.IllegalStatusTransitionException;
import com.flightcontrol.repository.FlightRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({FlightService.class, ClockConfig.class})
class FlightTransitionServiceTest {

    private final FlightService flightService;
    private final FlightRepository flightRepository;
    private final TestEntityManager entityManager;

    @Autowired
    FlightTransitionServiceTest(FlightService flightService, FlightRepository flightRepository,
                                TestEntityManager entityManager) {
        this.flightService = flightService;
        this.flightRepository = flightRepository;
        this.entityManager = entityManager;
    }

    @Test
    void appliesAndPersistsALegalTransition() {
        Long id = givenFlight("SVC100", FlightStatus.SCHEDULED).getId();

        FlightResponse response = flightService.transitionStatus(id, FlightStatus.DEPARTED);
        entityManager.flush();
        entityManager.clear();

        assertThat(response.status()).isEqualTo(FlightStatus.DEPARTED);
        assertThat(flightRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(FlightStatus.DEPARTED);
    }


    @Test
    void refreshesUpdatedAtWhenTransitioning() {
        Flight flight = givenFlight("SVC110", FlightStatus.SCHEDULED);
        LocalDateTime before = flight.getUpdatedAt();

        flightService.transitionStatus(flight.getId(), FlightStatus.DELAYED);
        entityManager.flush();

        assertThat(flight.getUpdatedAt()).isAfter(before);
    }

    @Test
    void rejectsATransitionThatIsNotInTheTable() {
        Long id = givenFlight("SVC200", FlightStatus.SCHEDULED).getId();

        assertThatThrownBy(() -> flightService.transitionStatus(id, FlightStatus.LANDED))
                .isInstanceOf(IllegalStatusTransitionException.class)
                .hasMessage("Flight SVC200 is SCHEDULED; allowed transitions: DELAYED, DEPARTED, CANCELLED");
    }

    @Test
    void rejectsATransitionToTheSameStatus() {
        Long id = givenFlight("SVC210", FlightStatus.SCHEDULED).getId();

        assertThatThrownBy(() -> flightService.transitionStatus(id, FlightStatus.SCHEDULED))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(FlightStatus.class)
    void rejectsEveryTransitionOutOfLanded(FlightStatus target) {
        Long id = givenFlight("SVC300", FlightStatus.LANDED).getId();

        assertThatThrownBy(() -> flightService.transitionStatus(id, target))
                .isInstanceOf(IllegalStatusTransitionException.class)
                .hasMessage("Flight SVC300 is LANDED; LANDED is terminal, no transitions are allowed");
    }

    @ParameterizedTest
    @EnumSource(FlightStatus.class)
    void rejectsEveryTransitionOutOfCancelled(FlightStatus target) {
        Long id = givenFlight("SVC310", FlightStatus.CANCELLED).getId();

        assertThatThrownBy(() -> flightService.transitionStatus(id, target))
                .isInstanceOf(IllegalStatusTransitionException.class)
                .hasMessage("Flight SVC310 is CANCELLED; CANCELLED is terminal, no transitions are allowed");
    }

    @Test
    void leavesTheStoredStatusUnchangedWhenATransitionIsRejected() {
        Long id = givenFlight("SVC400", FlightStatus.IN_AIR).getId();

        assertThatThrownBy(() -> flightService.transitionStatus(id, FlightStatus.SCHEDULED))
                .isInstanceOf(IllegalStatusTransitionException.class);
        entityManager.flush();
        entityManager.clear();

        assertThat(flightRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(FlightStatus.IN_AIR);
    }

    @Test
    void rejectsATransitionOnAFlightThatDoesNotExist() {
        assertThatThrownBy(() -> flightService.transitionStatus(9_999L, FlightStatus.DEPARTED))
                .isInstanceOf(FlightNotFoundException.class)
                .hasMessage("Flight 9999 not found");
    }

    private Flight givenFlight(String flightNumber, FlightStatus status) {
        LocalDateTime departure = LocalDateTime.now().plusDays(1);
        Flight flight = new Flight(flightNumber, "JFK", "LHR", departure, departure.plusHours(3));
        flight.setStatus(status);
        Flight saved = flightRepository.saveAndFlush(flight);
        entityManager.clear();
        return flightRepository.findById(saved.getId()).orElseThrow();
    }
}
