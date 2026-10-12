package com.gimble.bus_stop.controllers;

import com.gimble.bus_stop.dto.responses.TripResponse;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.services.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** The kid's paired phone: see today's trips and confirm pickup/arrival. Nothing else. */
@RestController
@RequestMapping("/v1/kid")
@RequiredArgsConstructor
public class KidController {

    private final TripService tripService;

    @GetMapping("/trips/today")
    public List<TripResponse> todaysTrips(@AuthenticationPrincipal User kid) {
        return tripService.kidTrips(kid);
    }

    @PostMapping("/passengers/{passengerId}/confirm-pickup")
    public TripResponse confirmPickup(@AuthenticationPrincipal User kid, @PathVariable UUID passengerId) {
        return tripService.kidConfirm(kid, passengerId, true);
    }

    @PostMapping("/passengers/{passengerId}/confirm-dropoff")
    public TripResponse confirmDropoff(@AuthenticationPrincipal User kid, @PathVariable UUID passengerId) {
        return tripService.kidConfirm(kid, passengerId, false);
    }
}
