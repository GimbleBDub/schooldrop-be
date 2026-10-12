package com.gimble.bus_stop.controllers;

import com.gimble.bus_stop.dto.responses.AdvertResponse;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.services.AdvertService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Sponsored cards for parents and school staff (one per screen, calm screens only). */
@RestController
@RequestMapping("/v1/adverts")
@RequiredArgsConstructor
public class AdvertController {

    private final AdvertService advertService;

    /** 200 with an advert, or 204 when there's nothing to show. */
    @GetMapping("/next")
    public ResponseEntity<AdvertResponse> next(@AuthenticationPrincipal User user) {
        return advertService.next(user).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/{advertId}/click")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void click(@PathVariable UUID advertId) {
        advertService.click(advertId);
    }
}
