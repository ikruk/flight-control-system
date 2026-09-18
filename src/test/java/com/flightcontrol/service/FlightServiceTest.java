package com.flightcontrol.service;

import com.flightcontrol.domain.Flight;
import com.flightcontrol.domain.FlightStatus;
import com.flightcontrol.dto.FlightRequest;
import com.flightcontrol.dto.FlightResponse;
import com.flightcontrol.exception.BusinessRuleViolationException;
import com.flightcontrol.exception.DuplicateFlightNumberException;
import com.flightcontrol.repository.FlightRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlightServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 10, 0);
    private static final LocalDateTime DEPARTURE = NOW.plusDays(1);
    private static final LocalDateTime ARRIVAL = DEPARTURE.plusHours(8);

    @Mock
    private FlightRepository flightRepository;

    private FlightService flightService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        flightService = new FlightService(flightRepository, fixedClock);
    }

    @Test
    void createsFlightAsScheduled() {
        savingReturnsTheFlight();

        FlightResponse response = flightService.create(validRequest());

        assertThat(response.status()).isEqualTo(FlightStatus.SCHEDULED);
        assertThat(response.flightNumber()).isEqualTo("BA117");
        assertThat(response.departureTime()).isEqualTo(DEPARTURE);
        assertThat(response.arrivalTime()).isEqualTo(ARRIVAL);
    }

    @Test
    void storesFlightNumberAndAirportCodesInUpperCase() {
        savingReturnsTheFlight();

        flightService.create(new FlightRequest("ba117", "lhr", "jfk", DEPARTURE, ARRIVAL));

        verify(flightRepository).existsByFlightNumberIgnoreCase("BA117");
        ArgumentCaptor<Flight> saved = ArgumentCaptor.forClass(Flight.class);
        verify(flightRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getFlightNumber()).isEqualTo("BA117");
        assertThat(saved.getValue().getOrigin()).isEqualTo("LHR");
        assertThat(saved.getValue().getDestination()).isEqualTo("JFK");
    }

    @Test
    void rejectsDepartureTimeInThePast() {
        LocalDateTime past = NOW.minusMinutes(1);
        FlightRequest request = new FlightRequest("BA117", "LHR", "JFK", past, past.plusHours(8));

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsOnly(entry("departureTime", "must be in the future")));
    }

    @Test
    void rejectsDepartureTimeEqualToNow() {
        FlightRequest request = new FlightRequest("BA117", "LHR", "JFK", NOW, NOW.plusHours(8));

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsOnly(entry("departureTime", "must be in the future")));
    }

    @Test
    void rejectsArrivalTimeBeforeDeparture() {
        FlightRequest request = new FlightRequest("BA117", "LHR", "JFK", DEPARTURE, DEPARTURE.minusMinutes(1));

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsOnly(entry("arrivalTime", "must be after departure time")));
    }

    @Test
    void rejectsArrivalTimeEqualToDeparture() {
        FlightRequest request = new FlightRequest("BA117", "LHR", "JFK", DEPARTURE, DEPARTURE);

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsOnly(entry("arrivalTime", "must be after departure time")));
    }

    @Test
    void rejectsSameOriginAndDestination() {
        FlightRequest request = new FlightRequest("BA117", "LHR", "LHR", DEPARTURE, ARRIVAL);

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsOnly(entry("destination", "must differ from origin")));
    }

    @Test
    void rejectsSameOriginAndDestinationDifferingOnlyByCase() {
        FlightRequest request = new FlightRequest("BA117", "lhr", "LHR", DEPARTURE, ARRIVAL);

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors())
                                .containsOnly(entry("destination", "must differ from origin")));
    }

    @Test
    void reportsEveryViolatedRuleInOneException() {
        LocalDateTime past = NOW.minusHours(1);
        FlightRequest request = new FlightRequest("BA117", "LHR", "LHR", past, past.minusHours(1));

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex ->
                        assertThat(ex.getFieldErrors()).containsOnly(
                                entry("departureTime", "must be in the future"),
                                entry("arrivalTime", "must be after departure time"),
                                entry("destination", "must differ from origin")));
    }

    @Test
    void rejectsDuplicateFlightNumberIgnoringCase() {
        when(flightRepository.existsByFlightNumberIgnoreCase("BA117")).thenReturn(true);
        FlightRequest request = new FlightRequest("ba117", "LHR", "JFK", DEPARTURE, ARRIVAL);

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOf(DuplicateFlightNumberException.class)
                .hasMessage("Flight number BA117 already exists");
        verify(flightRepository, never()).saveAndFlush(any());
    }

    @Test
    void skipsUniquenessCheckWhenDataRulesFail() {
        FlightRequest request = new FlightRequest("BA117", "LHR", "LHR", DEPARTURE, ARRIVAL);

        assertThatThrownBy(() -> flightService.create(request))
                .isInstanceOf(BusinessRuleViolationException.class);
        verifyNoInteractions(flightRepository);
    }

    private void savingReturnsTheFlight() {
        when(flightRepository.saveAndFlush(any(Flight.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static FlightRequest validRequest() {
        return new FlightRequest("BA117", "LHR", "JFK", DEPARTURE, ARRIVAL);
    }
}
