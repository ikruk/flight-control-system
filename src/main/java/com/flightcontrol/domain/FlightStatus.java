package com.flightcontrol.domain;

import java.util.EnumSet;
import java.util.Set;

import static java.util.Collections.unmodifiableSet;

public enum FlightStatus {
    SCHEDULED,
    DELAYED,
    DEPARTED,
    IN_AIR,
    LANDED,
    CANCELLED;

    /**
     * The transition table, encoded once. The switch is exhaustive with no default,
     * so a new status will not compile until its transitions are declared here.
     * EnumSet iterates in declaration order, which keeps the order stable for clients.
     */
    public Set<FlightStatus> allowedNextStatuses() {
        return switch (this) {
            case SCHEDULED -> unmodifiableSet(EnumSet.of(DELAYED, DEPARTED, CANCELLED));
            case DELAYED -> unmodifiableSet(EnumSet.of(DEPARTED, CANCELLED));
            case DEPARTED -> unmodifiableSet(EnumSet.of(IN_AIR));
            case IN_AIR -> unmodifiableSet(EnumSet.of(LANDED));
            case LANDED, CANCELLED -> unmodifiableSet(EnumSet.noneOf(FlightStatus.class));
        };
    }

    public boolean canTransitionTo(FlightStatus target) {
        return allowedNextStatuses().contains(target);
    }

    public boolean isTerminal() {
        return allowedNextStatuses().isEmpty();
    }
}
