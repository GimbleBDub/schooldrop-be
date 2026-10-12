package com.gimble.bus_stop.controllers;

import com.gimble.bus_stop.dto.requests.ReasonRequest;
import com.gimble.bus_stop.dto.requests.UpdateSchoolRequest;
import com.gimble.bus_stop.dto.responses.InviteResponse;
import com.gimble.bus_stop.dto.responses.PageResponse;
import com.gimble.bus_stop.dto.responses.SchoolStaffResponse;
import com.gimble.bus_stop.dto.responses.SchoolChildResponse;
import com.gimble.bus_stop.dto.responses.SchoolResponse;
import com.gimble.bus_stop.dto.responses.TripResponse;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.EnrollmentStatus;
import com.gimble.bus_stop.enums.SchoolRole;
import com.gimble.bus_stop.services.TripService.HandoverStage;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import com.gimble.bus_stop.services.SchoolService;
import com.gimble.bus_stop.services.TripService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** School staff (mostly on web): their school, enrollment confirmations, and today's gate list. */
@RestController
@RequestMapping("/v1/school")
@RequiredArgsConstructor
public class SchoolController {

    private final SchoolService schoolService;
    private final TripService tripService;

    @GetMapping("/me")
    public SchoolResponse mySchool(@AuthenticationPrincipal User staff) {
        return schoolService.getMySchool(staff);
    }

    @PutMapping("/me")
    public SchoolResponse updateMySchool(@AuthenticationPrincipal User staff,
                                         @Valid @RequestBody UpdateSchoolRequest request) {
        return schoolService.updateMySchool(staff, request);
    }

    @GetMapping("/children")
    public PageResponse<SchoolChildResponse> children(@AuthenticationPrincipal User staff,
                                                      @RequestParam(required = false) EnrollmentStatus status,
                                                      @PageableDefault(size = 50) Pageable pageable) {
        return schoolService.listChildren(staff, status, pageable);
    }

    @PostMapping("/children/{childId}/confirm")
    public SchoolChildResponse confirm(@AuthenticationPrincipal User staff, @PathVariable UUID childId) {
        return schoolService.decideEnrollment(staff, childId, true, null);
    }

    @PostMapping("/children/{childId}/reject")
    public SchoolChildResponse reject(@AuthenticationPrincipal User staff, @PathVariable UUID childId,
                                      @Valid @RequestBody ReasonRequest request) {
        return schoolService.decideEnrollment(staff, childId, false, request.reason());
    }

    @GetMapping("/trips")
    public List<TripResponse> trips(@AuthenticationPrincipal User staff,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return tripService.schoolTrips(staff, date);
    }

    /** Any staff member: release a child to the authorised driver (afternoon). */
    @PostMapping("/passengers/{passengerId}/release")
    public TripResponse release(@AuthenticationPrincipal User staff, @PathVariable UUID passengerId) {
        return tripService.schoolConfirm(staff, passengerId, HandoverStage.PICKUP);
    }

    /** Any staff member: confirm a child arrived at school (morning). */
    @PostMapping("/passengers/{passengerId}/arrived")
    public TripResponse arrived(@AuthenticationPrincipal User staff, @PathVariable UUID passengerId) {
        return tripService.schoolConfirm(staff, passengerId, HandoverStage.DROPOFF);
    }

    @PostMapping("/passengers/{passengerId}/dispute")
    public TripResponse dispute(@AuthenticationPrincipal User staff, @PathVariable UUID passengerId,
                                @RequestBody(required = false) ReasonRequest request) {
        return tripService.dispute(staff, passengerId, request != null ? request.reason() : null, null);
    }

    // ─── Staff (admins invite and remove; everyone can see the list) ─────────

    public record StaffInviteRequest(@NotNull SchoolRole role, @Size(max = 120) String name) {
    }

    @GetMapping("/staff")
    public SchoolStaffResponse staff(@AuthenticationPrincipal User staff) {
        return schoolService.listStaff(staff);
    }

    @PostMapping("/staff/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse inviteStaff(@AuthenticationPrincipal User admin, @Valid @RequestBody StaffInviteRequest request) {
        return schoolService.inviteStaff(admin, request.role(), request.name());
    }

    @DeleteMapping("/staff/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvite(@AuthenticationPrincipal User admin, @PathVariable UUID inviteId) {
        schoolService.revokeInvite(admin, inviteId);
    }

    @DeleteMapping("/staff/{staffId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeStaff(@AuthenticationPrincipal User admin, @PathVariable UUID staffId) {
        schoolService.removeStaff(admin, staffId);
    }
}
