package com.flightcontrol.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlightStatusTest {

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
}
