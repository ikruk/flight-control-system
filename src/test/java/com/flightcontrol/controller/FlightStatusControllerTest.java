package com.flightcontrol.controller;

import com.flightcontrol.domain.Flight;
import com.flightcontrol.domain.FlightStatus;
import com.flightcontrol.repository.FlightRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FlightStatusControllerTest {

    private final MockMvc mockMvc;
    private final FlightRepository flightRepository;

    @Autowired
    FlightStatusControllerTest(MockMvc mockMvc, FlightRepository flightRepository) {
        this.mockMvc = mockMvc;
        this.flightRepository = flightRepository;
    }

    @Test
    void appliesALegalTransitionAndReturnsTheUpdatedFlight() throws Exception {
        Long id = givenFlight("WEB100", FlightStatus.SCHEDULED);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DEPARTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.flightNumber").value("WEB100"))
                .andExpect(jsonPath("$.status").value("DEPARTED"))
                .andExpect(jsonPath("$.allowedNextStatuses[0]").value("IN_AIR"))
                .andExpect(jsonPath("$.allowedNextStatuses.length()").value(1))
                .andExpect(jsonPath("$.origin").value("JFK"))
                .andExpect(jsonPath("$.destination").value("LHR"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void listsEveryAllowedNextStatusInDeclarationOrder() throws Exception {
        Long id = givenFlight("WEB110", FlightStatus.DEPARTED);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_AIR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_AIR"))
                .andExpect(jsonPath("$.allowedNextStatuses[0]").value("LANDED"))
                .andExpect(jsonPath("$.allowedNextStatuses.length()").value(1));
    }

    @Test
    void listsMultipleAllowedNextStatusesInDeclarationOrder() throws Exception {
        Long id = givenFlight("WEB130", FlightStatus.SCHEDULED);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELAYED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELAYED"))
                .andExpect(jsonPath("$.allowedNextStatuses[0]").value("DEPARTED"))
                .andExpect(jsonPath("$.allowedNextStatuses[1]").value("CANCELLED"))
                .andExpect(jsonPath("$.allowedNextStatuses.length()").value(2));
    }

    @Test
    void reportsNoAllowedNextStatusesOnceAFlightIsTerminal() throws Exception {
        Long id = givenFlight("WEB120", FlightStatus.IN_AIR);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LANDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LANDED"))
                .andExpect(jsonPath("$.allowedNextStatuses.length()").value(0));
    }

    @Test
    void returns404WithTheErrorContractWhenTheFlightDoesNotExist() throws Exception {
        mockMvc.perform(patch("/api/flights/{id}/status", 9_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DEPARTED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("FlightNotFound"))
                .andExpect(jsonPath("$.message").value("Flight 9999 not found"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void returns409NamingTheCurrentStatusAndAllowedTransitions() throws Exception {
        Long id = givenFlight("WEB200", FlightStatus.SCHEDULED);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LANDED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("IllegalStatusTransition"))
                .andExpect(jsonPath("$.message")
                        .value("Flight WEB200 is SCHEDULED; allowed transitions: DELAYED, DEPARTED, CANCELLED"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void returns409WhenTheFlightIsInATerminalStatus() throws Exception {
        Long id = givenFlight("WEB210", FlightStatus.CANCELLED);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SCHEDULED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IllegalStatusTransition"))
                .andExpect(jsonPath("$.message")
                        .value("Flight WEB210 is CANCELLED; CANCELLED is terminal, no transitions are allowed"));
    }

    @Test
    void returns400WhenTheStatusIsMissing() throws Exception {
        Long id = givenFlight("WEB300", FlightStatus.SCHEDULED);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("ValidationFailed"))
                .andExpect(jsonPath("$.fieldErrors.status").value("must not be null"));
    }

    @Test
    void returns400ListingTheKnownStatusesWhenTheStatusIsNotARealOne() throws Exception {
        Long id = givenFlight("WEB310", FlightStatus.SCHEDULED);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BOARDING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("ValidationFailed"))
                .andExpect(jsonPath("$.fieldErrors.status")
                        .value("must be one of: SCHEDULED, DELAYED, DEPARTED, IN_AIR, LANDED, CANCELLED"));
    }

    @Test
    void leavesTheFlightUntouchedWhenATransitionIsRejected() throws Exception {
        Long id = givenFlight("WEB400", FlightStatus.IN_AIR);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SCHEDULED\"}"))
                .andExpect(status().isConflict());

        org.assertj.core.api.Assertions.assertThat(
                        flightRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(FlightStatus.IN_AIR);
    }

    private Long givenFlight(String flightNumber, FlightStatus status) {
        LocalDateTime departure = LocalDateTime.now().plusDays(1);
        Flight flight = new Flight(flightNumber, "JFK", "LHR", departure, departure.plusHours(3));
        flight.setStatus(status);
        return flightRepository.saveAndFlush(flight).getId();
    }
}
