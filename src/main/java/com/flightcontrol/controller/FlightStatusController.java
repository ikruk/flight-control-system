package com.flightcontrol.controller;

import com.flightcontrol.dto.FlightResponse;
import com.flightcontrol.dto.StatusTransitionRequest;
import com.flightcontrol.service.FlightService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/flights")
public class FlightStatusController {

    private final FlightService flightService;

    public FlightStatusController(FlightService flightService) {
        this.flightService = flightService;
    }

    @PatchMapping("/{id}/status")
    public FlightResponse transitionStatus(@PathVariable Long id,
                                           @Valid @RequestBody StatusTransitionRequest request) {
        return flightService.transitionStatus(id, request.status());
    }
}
