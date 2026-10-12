package com.gimble.bus_stop.services;

import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.entities.VehicleColour;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.repositories.VehicleColourRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** The vehicle colour dropdown. Staff add colours; removing one hides it without touching existing vehicles. */
@Service
@RequiredArgsConstructor
public class VehicleColourService {

    private final VehicleColourRepository repository;
    private final AuditService auditService;

    public record ColourResponse(UUID id, String name, boolean active) {
        static ColourResponse of(VehicleColour c) {
            return new ColourResponse(c.getId(), c.getName(), c.isActive());
        }
    }

    @Transactional(readOnly = true)
    public List<String> activeNames() {
        return repository.findByActiveTrueOrderByNameAsc().stream().map(VehicleColour::getName).toList();
    }

    @Transactional(readOnly = true)
    public List<ColourResponse> all() {
        return repository.findAllByOrderByNameAsc().stream().map(ColourResponse::of).toList();
    }

    /** Adds a colour, or brings back one that was removed. */
    @Transactional
    public ColourResponse add(User admin, String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 40) {
            throw BusinessException.badRequest("Enter a colour name (up to 40 characters)");
        }
        String display = clean.substring(0, 1).toUpperCase() + clean.substring(1);
        VehicleColour colour = repository.findByNameIgnoreCase(clean)
                .orElseGet(() -> repository.save(VehicleColour.builder().name(display).build()));
        colour.setActive(true);
        auditService.record(admin, "VEHICLE_COLOUR_ADDED", "VehicleColour", colour.getId(), colour.getName());
        return ColourResponse.of(colour);
    }

    @Transactional
    public ColourResponse setActive(User admin, UUID id, boolean active) {
        VehicleColour colour = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Colour not found"));
        colour.setActive(active);
        auditService.record(admin, active ? "VEHICLE_COLOUR_RESTORED" : "VEHICLE_COLOUR_REMOVED", "VehicleColour", id, colour.getName());
        return ColourResponse.of(colour);
    }

    /** The colour as listed (normalised spelling), or an error if it's not on the list. */
    @Transactional(readOnly = true)
    public String requireListed(String colour) {
        if (colour == null || colour.isBlank()) {
            return null;
        }
        return repository.findByNameIgnoreCase(colour.trim())
                .filter(VehicleColour::isActive)
                .map(VehicleColour::getName)
                .orElseThrow(() -> BusinessException.badRequest("Choose the colour from the list"));
    }
}
