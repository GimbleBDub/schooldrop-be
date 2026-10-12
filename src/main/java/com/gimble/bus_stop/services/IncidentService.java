package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.requests.IncidentRequest;
import com.gimble.bus_stop.dto.requests.IncidentUpdateRequest;
import com.gimble.bus_stop.dto.responses.IncidentResponse;
import com.gimble.bus_stop.dto.responses.PageResponse;
import com.gimble.bus_stop.entities.*;
import com.gimble.bus_stop.enums.IncidentStatus;
import com.gimble.bus_stop.enums.IncidentType;
import com.gimble.bus_stop.enums.Role;
import com.gimble.bus_stop.enums.StaffPermission;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.repositories.ChildRepository;
import com.gimble.bus_stop.repositories.DriverProfileRepository;
import com.gimble.bus_stop.repositories.IncidentRepository;
import com.gimble.bus_stop.repositories.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentService {

    private final IncidentRepository incidentRepository;
    private final TripRepository tripRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final ChildRepository childRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final Clock clock;

    /** Anyone signed in can report; SOS alerts go to support immediately and to affected parents. */
    @Transactional
    public IncidentResponse report(User reporter, IncidentRequest request) {
        Trip trip = request.tripId() != null ? tripRepository.findById(request.tripId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found")) : null;
        DriverProfile driver = request.driverId() != null ? driverProfileRepository.findById(request.driverId())
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found"))
                : trip != null && reporter.getRole() != Role.DRIVER ? trip.getDriver() : null;

        Incident incident = incidentRepository.save(Incident.builder()
                .reporter(reporter)
                .trip(trip)
                .driver(driver)
                .type(request.type())
                .description(request.description())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .build());

        boolean sos = request.type() == IncidentType.SOS;
        String title = sos ? "SOS from " + reporter.getFullName() : "New " + request.type() + " report";
        notificationService.notifyStaff(StaffPermission.SUPPORT, sos ? "SOS" : "INCIDENT", title,
                request.description(), incident.getId());

        if (sos) {
            log.warn("SOS incident {} raised by user {}", incident.getId(), reporter.getId());
            if (reporter.getRole() == Role.KID) {
                childRepository.findByKidUserId(reporter.getId()).ifPresent(child ->
                        notificationService.notify(child.getParent(), "SOS", "SOS from " + child.getFirstName(),
                                child.getFirstName() + " pressed SOS: " + request.description(), incident.getId()));
            }
            if (trip != null && reporter.getRole() == Role.DRIVER) {
                trip.getPassengers().forEach(p -> notificationService.notify(p.getChild().getParent(), "SOS",
                        "Emergency on " + p.getChild().getFirstName() + "'s trip",
                        "The driver raised an emergency alert. Our support team has been notified and will call you.",
                        incident.getId()));
            }
        }
        return IncidentResponse.from(incident);
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse> listMine(User reporter) {
        return incidentRepository.findByReporterIdOrderByCreatedAtDesc(reporter.getId()).stream()
                .map(IncidentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<IncidentResponse> adminSearch(IncidentStatus status, Pageable pageable) {
        return PageResponse.from(incidentRepository.adminSearch(status, pageable).map(IncidentResponse::from));
    }

    @Transactional
    public IncidentResponse adminUpdate(User admin, UUID incidentId, IncidentUpdateRequest request) {
        Incident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found"));
        incident.setStatus(request.status());
        if (request.resolution() != null) {
            incident.setResolution(request.resolution());
        }
        if (request.status() == IncidentStatus.RESOLVED) {
            incident.setResolvedBy(admin);
            incident.setResolvedAt(Instant.now(clock));
            notificationService.notify(incident.getReporter(), "INCIDENT", "Report resolved",
                    "Your report has been resolved" + (request.resolution() != null ? ": " + request.resolution() : "."),
                    incident.getId());
        }
        auditService.record(admin, "INCIDENT_" + request.status(), "Incident", incident.getId(), request.resolution());
        return IncidentResponse.from(incident);
    }
}
