package com.gimble.bus_stop.controllers;

import com.gimble.bus_stop.dto.billing.BillingDtos.DownloadLink;
import com.gimble.bus_stop.dto.billing.BillingDtos.FinanceReport;
import com.gimble.bus_stop.dto.billing.BillingDtos.InvoiceDetail;
import com.gimble.bus_stop.dto.billing.BillingDtos.InvoiceSummary;
import com.gimble.bus_stop.dto.requests.*;
import com.gimble.bus_stop.dto.responses.*;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.*;
import com.gimble.bus_stop.services.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Back office (web admin panel). The whole path requires ROLE_ADMIN (SecurityConfig);
 * each area additionally needs the matching staff permission. SUPER_ADMIN can do everything.
 */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    static final String ANY_STAFF = "hasRole('ADMIN')";
    static final String SUPER = "hasAuthority('PERM_SUPER_ADMIN')";
    static final String VERIFIER = "hasAnyAuthority('PERM_SUPER_ADMIN', 'PERM_VERIFIER')";
    static final String SUPPORT = "hasAnyAuthority('PERM_SUPER_ADMIN', 'PERM_SUPPORT')";
    static final String FINANCE = "hasAnyAuthority('PERM_SUPER_ADMIN', 'PERM_FINANCE')";
    static final String MARKETING = "hasAnyAuthority('PERM_SUPER_ADMIN', 'PERM_MARKETING')";
    // Reads: the owning area, plus VIEWER (read-only staff such as an accountant or an investor)
    static final String READ_PEOPLE = "hasAnyAuthority('PERM_SUPER_ADMIN', 'PERM_VERIFIER', 'PERM_SUPPORT', 'PERM_VIEWER')";
    static final String READ_SUPPORT = "hasAnyAuthority('PERM_SUPER_ADMIN', 'PERM_SUPPORT', 'PERM_VIEWER')";
    static final String READ_FINANCE = "hasAnyAuthority('PERM_SUPER_ADMIN', 'PERM_FINANCE', 'PERM_VIEWER')";
    static final String READ_BOOKINGS = "hasAnyAuthority('PERM_SUPER_ADMIN', 'PERM_SUPPORT', 'PERM_FINANCE', 'PERM_VIEWER')";
    static final String READ_MARKETING = "hasAnyAuthority('PERM_SUPER_ADMIN', 'PERM_MARKETING', 'PERM_VIEWER')";

    private final AdminService adminService;
    private final SchoolService schoolService;
    private final DriverService driverService;
    private final BookingService bookingService;
    private final PaymentService paymentService;
    private final PayoutService payoutService;
    private final IncidentService incidentService;
    private final TripService tripService;
    private final AdvertService advertService;
    private final BillingService billingService;
    private final VehicleColourService vehicleColourService;

    // ─── Dashboard ───────────────────────────────────────────────────────────

    @GetMapping("/dashboard")
    @PreAuthorize(ANY_STAFF)
    public DashboardResponse dashboard() {
        return adminService.dashboard();
    }

    @GetMapping("/trips/live")
    @PreAuthorize(READ_SUPPORT)
    public List<TripResponse> liveTrips() {
        return tripService.liveTrips();
    }

    // ─── Users & staff ───────────────────────────────────────────────────────

    @GetMapping("/users")
    @PreAuthorize(READ_SUPPORT)
    public PageResponse<AdminUserResponse> users(@RequestParam(required = false) Role role,
                                                 @RequestParam(required = false) String query,
                                                 @PageableDefault(size = 25) Pageable pageable) {
        return adminService.searchUsers(role, query, pageable);
    }

    @GetMapping("/users/{userId}")
    @PreAuthorize(READ_SUPPORT)
    public AdminUserResponse user(@PathVariable UUID userId) {
        return adminService.getUser(userId);
    }

    @PostMapping("/users/{userId}/disable")
    @PreAuthorize(SUPPORT)
    public AdminUserResponse disableUser(@AuthenticationPrincipal User admin, @PathVariable UUID userId,
                                         @Valid @RequestBody ReasonRequest request) {
        return adminService.setEnabled(admin, userId, false, request.reason());
    }

    @PostMapping("/users/{userId}/enable")
    @PreAuthorize(SUPPORT)
    public AdminUserResponse enableUser(@AuthenticationPrincipal User admin, @PathVariable UUID userId) {
        return adminService.setEnabled(admin, userId, true, null);
    }

    @GetMapping("/staff")
    @PreAuthorize(SUPER)
    public List<AdminUserResponse> staff() {
        return adminService.listStaff();
    }

    @PostMapping("/staff/{userId}/reset-password")
    @PreAuthorize(SUPER)
    public AdminUserResponse resetStaffPassword(@AuthenticationPrincipal User admin, @PathVariable UUID userId) {
        return adminService.resetStaffPassword(admin, userId);
    }

    @PostMapping("/staff")
    @PreAuthorize(SUPER)
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUserResponse createStaff(@AuthenticationPrincipal User admin, @Valid @RequestBody StaffRequest request) {
        return adminService.createStaff(admin, request);
    }

    @PutMapping("/staff/{userId}/permissions")
    @PreAuthorize(SUPER)
    public AdminUserResponse setPermissions(@AuthenticationPrincipal User admin, @PathVariable UUID userId,
                                            @Valid @RequestBody PermissionsRequest request) {
        return adminService.setPermissions(admin, userId, request.permissions());
    }

    // ─── Schools ─────────────────────────────────────────────────────────────

    @GetMapping("/schools")
    @PreAuthorize(READ_PEOPLE)
    public PageResponse<SchoolResponse> schools(@RequestParam(required = false) SchoolStatus status,
                                                @RequestParam(required = false) String query,
                                                @PageableDefault(size = 25, sort = "createdAt") Pageable pageable) {
        return schoolService.adminSearch(status, query, pageable);
    }

    /** Schools are added by SchoolDrop staff, pinned on the map (they're the list families and drivers pick from). */
    @PostMapping("/schools")
    @PreAuthorize(VERIFIER + " or " + SUPPORT)
    @ResponseStatus(HttpStatus.CREATED)
    public SchoolResponse createSchool(@AuthenticationPrincipal User admin, @Valid @RequestBody AdminSchoolRequest request) {
        return schoolService.adminCreate(admin, request);
    }

    @PutMapping("/schools/{schoolId}")
    @PreAuthorize(VERIFIER + " or " + SUPPORT)
    public SchoolResponse updateSchool(@AuthenticationPrincipal User admin, @PathVariable UUID schoolId,
                                       @Valid @RequestBody AdminSchoolRequest request) {
        return schoolService.adminUpdate(admin, schoolId, request);
    }

    public record SchoolInviteRequest(SchoolRole role, @jakarta.validation.constraints.Size(max = 120) String name) {
    }

    /** An invite code for the school's admin (or staff) to join it. */
    @PostMapping("/schools/{schoolId}/invites")
    @PreAuthorize(VERIFIER + " or " + SUPPORT)
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse inviteSchoolStaff(@AuthenticationPrincipal User admin, @PathVariable UUID schoolId,
                                            @RequestBody SchoolInviteRequest request) {
        return schoolService.adminInvite(admin, schoolId, request.role() != null ? request.role() : SchoolRole.ADMIN, request.name());
    }

    @PostMapping("/schools/{schoolId}/verify")
    @PreAuthorize(VERIFIER)
    public SchoolResponse verifySchool(@AuthenticationPrincipal User admin, @PathVariable UUID schoolId) {
        return schoolService.adminSetStatus(admin, schoolId, SchoolStatus.VERIFIED, null);
    }

    @PostMapping("/schools/{schoolId}/reject")
    @PreAuthorize(VERIFIER)
    public SchoolResponse rejectSchool(@AuthenticationPrincipal User admin, @PathVariable UUID schoolId,
                                       @Valid @RequestBody ReasonRequest request) {
        return schoolService.adminSetStatus(admin, schoolId, SchoolStatus.REJECTED, request.reason());
    }

    @PostMapping("/schools/{schoolId}/suspend")
    @PreAuthorize(VERIFIER + " or " + SUPPORT)
    public SchoolResponse suspendSchool(@AuthenticationPrincipal User admin, @PathVariable UUID schoolId,
                                        @Valid @RequestBody ReasonRequest request) {
        return schoolService.adminSetStatus(admin, schoolId, SchoolStatus.SUSPENDED, request.reason());
    }

    // ─── Driver verification ─────────────────────────────────────────────────

    @GetMapping("/drivers")
    @PreAuthorize(READ_PEOPLE)
    public PageResponse<DriverSummaryResponse> drivers(@RequestParam(required = false) DriverStatus status,
                                                       @RequestParam(required = false) String query,
                                                       @PageableDefault(size = 25) Pageable pageable) {
        return driverService.adminSearch(status, query, pageable);
    }

    @GetMapping("/drivers/{driverId}")
    @PreAuthorize(VERIFIER + " or " + SUPPORT)
    public DriverProfileResponse driver(@AuthenticationPrincipal User admin, @PathVariable UUID driverId) {
        return driverService.adminGet(admin, driverId);
    }

    @PostMapping("/drivers/{driverId}/approve")
    @PreAuthorize(VERIFIER)
    public DriverProfileResponse approveDriver(@AuthenticationPrincipal User admin, @PathVariable UUID driverId) {
        return driverService.approve(admin, driverId);
    }

    @PostMapping("/drivers/{driverId}/reject")
    @PreAuthorize(VERIFIER)
    public DriverProfileResponse rejectDriver(@AuthenticationPrincipal User admin, @PathVariable UUID driverId,
                                              @Valid @RequestBody ReasonRequest request) {
        return driverService.reject(admin, driverId, request.reason());
    }

    @PostMapping("/drivers/{driverId}/suspend")
    @PreAuthorize(VERIFIER + " or " + SUPPORT)
    public DriverProfileResponse suspendDriver(@AuthenticationPrincipal User admin, @PathVariable UUID driverId,
                                               @Valid @RequestBody ReasonRequest request) {
        return driverService.suspend(admin, driverId, request.reason());
    }

    @PostMapping("/documents/{documentId}/review")
    @PreAuthorize(VERIFIER)
    public DocumentResponse reviewDocument(@AuthenticationPrincipal User admin, @PathVariable UUID documentId,
                                           @Valid @RequestBody DocumentReviewRequest request) {
        return driverService.reviewDocument(admin, documentId, request);
    }

    @GetMapping("/documents/{documentId}/file")
    @PreAuthorize(VERIFIER)
    public ResponseEntity<?> documentFile(@PathVariable UUID documentId) {
        return Files.inline(driverService.adminDownloadDocument(documentId));
    }

    // ─── Bookings, payments, payouts ─────────────────────────────────────────

    @GetMapping("/bookings")
    @PreAuthorize(READ_BOOKINGS)
    public PageResponse<BookingResponse> bookings(@RequestParam(required = false) BookingStatus status,
                                                  @PageableDefault(size = 25) Pageable pageable) {
        return bookingService.adminSearch(status, pageable);
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    @PreAuthorize(SUPPORT)
    public BookingResponse cancelBooking(@AuthenticationPrincipal User admin, @PathVariable UUID bookingId,
                                         @RequestParam(defaultValue = "false") boolean refund,
                                         @Valid @RequestBody ReasonRequest request) {
        return bookingService.adminCancel(admin, bookingId, request.reason(), refund);
    }

    @GetMapping("/payments")
    @PreAuthorize(READ_FINANCE)
    public PageResponse<PaymentResponse> payments(@RequestParam(required = false) PaymentStatus status,
                                                  @PageableDefault(size = 25) Pageable pageable) {
        return paymentService.adminSearch(status, pageable);
    }

    @PostMapping("/payments/{paymentId}/refund")
    @PreAuthorize(FINANCE)
    public PaymentResponse refund(@AuthenticationPrincipal User admin, @PathVariable UUID paymentId,
                                  @Valid @RequestBody ReasonRequest request) {
        return paymentService.adminRefund(admin, paymentId, request.reason());
    }

    @GetMapping("/payouts")
    @PreAuthorize(READ_FINANCE)
    public PageResponse<PayoutResponse> payouts(@RequestParam(required = false) PayoutStatus status,
                                                @PageableDefault(size = 25) Pageable pageable) {
        return payoutService.adminSearch(status, pageable);
    }

    @PostMapping("/payouts/run")
    @PreAuthorize(FINANCE)
    public Map<String, Integer> runPayouts(@AuthenticationPrincipal User admin) {
        return Map.of("created", payoutService.createPayouts(admin));
    }

    public record PayoutPaidRequest(@jakarta.validation.constraints.NotBlank String reference) {
    }

    @PostMapping("/payouts/{payoutId}/paid")
    @PreAuthorize(FINANCE)
    public PayoutResponse payoutPaid(@AuthenticationPrincipal User admin, @PathVariable UUID payoutId,
                                     @Valid @RequestBody PayoutPaidRequest request) {
        return payoutService.markPaid(admin, payoutId, request.reference());
    }

    @PostMapping("/payouts/{payoutId}/failed")
    @PreAuthorize(FINANCE)
    public PayoutResponse payoutFailed(@AuthenticationPrincipal User admin, @PathVariable UUID payoutId,
                                       @Valid @RequestBody ReasonRequest request) {
        return payoutService.markFailed(admin, payoutId, request.reason());
    }

    // ─── Invoices & finance ──────────────────────────────────────────────────

    @GetMapping("/invoices")
    @PreAuthorize(READ_FINANCE + " or " + SUPPORT)
    public PageResponse<InvoiceSummary> invoices(@RequestParam(required = false) InvoiceType type,
                                                 @RequestParam(required = false) String query,
                                                 @PageableDefault(size = 25) Pageable pageable) {
        return billingService.adminSearch(type, query, pageable);
    }

    @GetMapping("/invoices/{invoiceId}")
    @PreAuthorize(READ_FINANCE + " or " + SUPPORT)
    public InvoiceDetail invoice(@AuthenticationPrincipal User admin, @PathVariable UUID invoiceId) {
        return billingService.invoice(admin, invoiceId);
    }

    @PostMapping("/invoices/{invoiceId}/link")
    @PreAuthorize(READ_FINANCE + " or " + SUPPORT)
    public DownloadLink invoiceLink(@AuthenticationPrincipal User admin, @PathVariable UUID invoiceId) {
        return billingService.downloadLink(admin, invoiceId);
    }

    /** SchoolDrop's money by month: collected, commission, listing fees, adverts, refunds, payouts. */
    @GetMapping("/finance")
    @PreAuthorize(READ_FINANCE)
    public FinanceReport finance(@RequestParam(defaultValue = "12") int months) {
        return billingService.financeReport(months);
    }

    // ─── Incidents ───────────────────────────────────────────────────────────

    @GetMapping("/incidents")
    @PreAuthorize(READ_SUPPORT)
    public PageResponse<IncidentResponse> incidents(@RequestParam(required = false) IncidentStatus status,
                                                    @PageableDefault(size = 25) Pageable pageable) {
        return incidentService.adminSearch(status, pageable);
    }

    @PutMapping("/incidents/{incidentId}")
    @PreAuthorize(SUPPORT)
    public IncidentResponse updateIncident(@AuthenticationPrincipal User admin, @PathVariable UUID incidentId,
                                           @Valid @RequestBody IncidentUpdateRequest request) {
        return incidentService.adminUpdate(admin, incidentId, request);
    }

    // ─── Adverts ─────────────────────────────────────────────────────────────

    @GetMapping("/adverts")
    @PreAuthorize(READ_MARKETING)
    public PageResponse<AdvertResponse> adverts(@RequestParam(required = false) AdvertStatus status,
                                                @PageableDefault(size = 25) Pageable pageable) {
        return advertService.adminSearch(status, pageable);
    }

    @PostMapping("/adverts/{advertId}/review")
    @PreAuthorize(MARKETING)
    public AdvertResponse reviewAdvert(@AuthenticationPrincipal User admin, @PathVariable UUID advertId,
                                       @Valid @RequestBody DocumentReviewRequest request) {
        return advertService.review(admin, advertId, request.approve(), request.note());
    }

    /** Outside advertisers who paid SchoolDrop directly: live immediately. */
    @PostMapping("/adverts")
    @PreAuthorize(MARKETING)
    @ResponseStatus(HttpStatus.CREATED)
    public AdvertResponse createAdvert(@AuthenticationPrincipal User admin, @Valid @RequestBody StaffAdvertRequest request) {
        return advertService.createForAdvertiser(admin, request);
    }

    @PostMapping("/adverts/{advertId}/pause")
    @PreAuthorize(MARKETING)
    public AdvertResponse pauseAdvert(@AuthenticationPrincipal User admin, @PathVariable UUID advertId) {
        return advertService.setPaused(admin, advertId, true);
    }

    @PostMapping("/adverts/{advertId}/resume")
    @PreAuthorize(MARKETING)
    public AdvertResponse resumeAdvert(@AuthenticationPrincipal User admin, @PathVariable UUID advertId) {
        return advertService.setPaused(admin, advertId, false);
    }

    // ─── Vehicle colours (the drivers' dropdown) ─────────────────────────────

    public record ColourRequest(@jakarta.validation.constraints.NotBlank String name) {
    }

    @GetMapping("/vehicle-colours")
    @PreAuthorize(ANY_STAFF)
    public List<VehicleColourService.ColourResponse> vehicleColours() {
        return vehicleColourService.all();
    }

    @PostMapping("/vehicle-colours")
    @PreAuthorize(VERIFIER + " or " + SUPPORT)
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleColourService.ColourResponse addVehicleColour(@AuthenticationPrincipal User admin,
                                                                @Valid @RequestBody ColourRequest request) {
        return vehicleColourService.add(admin, request.name());
    }

    @PostMapping("/vehicle-colours/{colourId}/{action:remove|restore}")
    @PreAuthorize(VERIFIER + " or " + SUPPORT)
    public VehicleColourService.ColourResponse setVehicleColour(@AuthenticationPrincipal User admin, @PathVariable UUID colourId,
                                                                @PathVariable String action) {
        return vehicleColourService.setActive(admin, colourId, action.equals("restore"));
    }

    // ─── School terms ────────────────────────────────────────────────────────

    @GetMapping("/terms")
    @PreAuthorize(ANY_STAFF)
    public List<SchoolTermResponse> terms() {
        return adminService.listTerms(false);
    }

    @PostMapping("/terms")
    @PreAuthorize(SUPER)
    @ResponseStatus(HttpStatus.CREATED)
    public SchoolTermResponse createTerm(@AuthenticationPrincipal User admin, @Valid @RequestBody SchoolTermRequest request) {
        return adminService.saveTerm(admin, null, request);
    }

    @PutMapping("/terms/{termId}")
    @PreAuthorize(SUPER)
    public SchoolTermResponse updateTerm(@AuthenticationPrincipal User admin, @PathVariable UUID termId,
                                         @Valid @RequestBody SchoolTermRequest request) {
        return adminService.saveTerm(admin, termId, request);
    }

    // ─── Audit ───────────────────────────────────────────────────────────────

    @GetMapping("/audit")
    @PreAuthorize(SUPER)
    public PageResponse<AuditLogResponse> audit(@RequestParam(required = false) String targetType,
                                                @RequestParam(required = false) UUID targetId,
                                                @RequestParam(required = false) UUID actorId,
                                                @PageableDefault(size = 50) Pageable pageable) {
        return adminService.auditLog(targetType, targetId, actorId, pageable);
    }
}
