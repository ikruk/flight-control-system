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
                .andExpect(jsonPath("$.allowedNextStatuses").value("IN_AIR"))
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
    void reportsNoAllowedNextStatusesOnceAFlightIsTerminal() throws Exception {
        Long id = givenFlight("WEB120", FlightStatus.IN_AIR);

        mockMvc.perform(patch("/api/flights/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LANDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LANDED"))
                .andExpect(jsonPath("$.allowedNextStatuses.length()").value(0));
    }

    private Long givenFlight(String flightNumber, FlightStatus status) {
        LocalDateTime departure = LocalDateTime.now().plusDays(1);
        Flight flight = new Flight(flightNumber, "JFK", "LHR", departure, departure.plusHours(3));
        flight.setStatus(status);
        return flightRepository.saveAndFlush(flight).getId();
    }
}
