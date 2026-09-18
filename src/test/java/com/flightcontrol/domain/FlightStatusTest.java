package com.flightcontrol.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.flightcontrol.domain.FlightStatus.CANCELLED;
import static com.flightcontrol.domain.FlightStatus.DELAYED;
import static com.flightcontrol.domain.FlightStatus.DEPARTED;
import static com.flightcontrol.domain.FlightStatus.IN_AIR;
import static com.flightcontrol.domain.FlightStatus.LANDED;
import static com.flightcontrol.domain.FlightStatus.SCHEDULED;
import static org.assertj.core.api.Assertions.assertThat;

class FlightStatusTest {

    /** The transition table, written out independently of the production code. */
    private static final Map<FlightStatus, Set<FlightStatus>> LEGAL_TRANSITIONS = Map.of(
            SCHEDULED, Set.of(DELAYED, DEPARTED, CANCELLED),
            DELAYED, Set.of(DEPARTED, CANCELLED),
            DEPARTED, Set.of(IN_AIR),
            IN_AIR, Set.of(LANDED),
            LANDED, Set.of(),
            CANCELLED, Set.of());

    @Test
    void definesExactlyTheSixLifecycleStatuses() {
        assertThat(FlightStatus.values()).containsExactly(
                FlightStatus.SCHEDULED,
                FlightStatus.DELAYED,
                FlightStatus.DEPARTED,
                FlightStatus.IN_AIR,
                FlightStatus.LANDED,
                FlightStatus.CANCELLED);
    }

    @ParameterizedTest(name = "{0} -> {1} legal={2}")
    @MethodSource("everyStatusPair")
    void enforcesTheTransitionTableForEveryStatusPair(FlightStatus from, FlightStatus to, boolean legal) {
        assertThat(from.canTransitionTo(to)).isEqualTo(legal);
    }

    @ParameterizedTest
    @EnumSource(FlightStatus.class)
    void exposesExactlyTheAllowedTargetsForEachStatus(FlightStatus status) {
        assertThat(status.allowedNextStatuses())
                .containsExactlyInAnyOrderElementsOf(LEGAL_TRANSITIONS.get(status));
    }

    @Test
    void listsAllowedTargetsInEnumDeclarationOrder() {
        assertThat(SCHEDULED.allowedNextStatuses()).containsExactly(DELAYED, DEPARTED, CANCELLED);
    }

    @Test
    void treatsLandedAndCancelledAsTerminal() {
        assertThat(LANDED.isTerminal()).isTrue();
        assertThat(CANCELLED.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = FlightStatus.class, names = {"SCHEDULED", "DELAYED", "DEPARTED", "IN_AIR"})
    void treatsEveryNonTerminalStatusAsNonTerminal(FlightStatus status) {
        assertThat(status.isTerminal()).isFalse();
    }

    private static Stream<Arguments> everyStatusPair() {
        return Arrays.stream(FlightStatus.values()).flatMap(from ->
                Arrays.stream(FlightStatus.values()).map(to ->
                        Arguments.of(from, to, LEGAL_TRANSITIONS.get(from).contains(to))));
    }
}
