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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /**
     * Exhaustive coverage over the full (source, target) cross product: every pair that is not
     * a legal transition must be rejected with the exact message the brief specifies. The pair
     * selection is allowed to ask production code "is this legal" ({@link FlightStatus#canTransitionTo}),
     * but the expected message text is built from a table kept independently here (ALLOWED_NEXT
     * below), never by calling {@code allowedNextStatuses()} / {@code isTerminal()} or the
     * exception class itself — otherwise a bug in production message-building would go undetected.
     */
    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @MethodSource("illegalTransitions")
    void rejectsEveryIllegalTransition(FlightStatus source, FlightStatus target) {
        String flightNumber = flightNumberFor(source, target);
        Long id = givenFlight(flightNumber, source).getId();

        assertThatThrownBy(() -> flightService.transitionStatus(id, target))
                .isInstanceOf(IllegalStatusTransitionException.class)
                .hasMessage(expectedMessage(source, flightNumber));
    }

    private static Stream<Arguments> illegalTransitions() {
        return Arrays.stream(FlightStatus.values())
                .flatMap(source -> Arrays.stream(FlightStatus.values())
                        .filter(target -> !source.canTransitionTo(target))
                        .map(target -> Arguments.of(source, target)));
    }

    // Independent restatement of the transition table from the spec, kept separate from
    // FlightStatus so this test does not validate production code against itself.
    private static final Map<FlightStatus, List<FlightStatus>> ALLOWED_NEXT = Map.of(
            FlightStatus.SCHEDULED, List.of(FlightStatus.DELAYED, FlightStatus.DEPARTED, FlightStatus.CANCELLED),
            FlightStatus.DELAYED, List.of(FlightStatus.DEPARTED, FlightStatus.CANCELLED),
            FlightStatus.DEPARTED, List.of(FlightStatus.IN_AIR),
            FlightStatus.IN_AIR, List.of(FlightStatus.LANDED),
            FlightStatus.LANDED, List.of(),
            FlightStatus.CANCELLED, List.of());

    private static String expectedMessage(FlightStatus source, String flightNumber) {
        List<FlightStatus> allowed = ALLOWED_NEXT.get(source);
        if (allowed.isEmpty()) {
            return "Flight %s is %s; %s is terminal, no transitions are allowed"
                    .formatted(flightNumber, source, source);
        }
        return "Flight %s is %s; allowed transitions: %s".formatted(
                flightNumber, source, allowed.stream().map(Enum::name).collect(Collectors.joining(", ")));
    }

    private static String flightNumberFor(FlightStatus source, FlightStatus target) {
        return "TX%d%d".formatted(source.ordinal(), target.ordinal());
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
