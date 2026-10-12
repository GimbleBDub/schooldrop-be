package com.gimble.bus_stop.controllers;

import com.gimble.bus_stop.dto.requests.IncidentRequest;
import com.gimble.bus_stop.dto.responses.IncidentResponse;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.services.IncidentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** SOS, safety concerns and complaints — any signed-in user. */
@RestController
@RequestMapping("/v1/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService incidentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentResponse report(@AuthenticationPrincipal User user, @Valid @RequestBody IncidentRequest request) {
        return incidentService.report(user, request);
    }

    @GetMapping
    public List<IncidentResponse> mine(@AuthenticationPrincipal User user) {
        return incidentService.listMine(user);
    }
}
