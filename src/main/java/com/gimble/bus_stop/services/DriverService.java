package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.requests.DocumentReviewRequest;
import com.gimble.bus_stop.dto.requests.DriverProfileRequest;
import com.gimble.bus_stop.dto.requests.VehicleRequest;
import com.gimble.bus_stop.dto.responses.DocumentResponse;
import com.gimble.bus_stop.dto.responses.DriverProfileResponse;
import com.gimble.bus_stop.dto.responses.DriverSummaryResponse;
import com.gimble.bus_stop.dto.responses.PageResponse;
import com.gimble.bus_stop.dto.responses.VehicleResponse;
import com.gimble.bus_stop.entities.*;
import com.gimble.bus_stop.enums.DocumentStatus;
import com.gimble.bus_stop.enums.DocumentType;
import com.gimble.bus_stop.enums.DriverStatus;
import com.gimble.bus_stop.enums.OperatorKind;
import com.gimble.bus_stop.enums.OperatorRole;
import com.gimble.bus_stop.enums.StaffPermission;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.exceptions.UnauthorizedException;
import com.gimble.bus_stop.repositories.DriverProfileRepository;
import com.gimble.bus_stop.repositories.TransportOfferRepository;
import com.gimble.bus_stop.repositories.VehicleRepository;
import com.gimble.bus_stop.repositories.VerificationDocumentRepository;
import com.gimble.bus_stop.storage.FileStorage;
import com.gimble.bus_stop.storage.UploadValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DriverService {

    private static final int EXPIRY_WARNING_DAYS = 14;

    private final DriverProfileRepository driverProfileRepository;
    private final VehicleRepository vehicleRepository;
    private final VerificationDocumentRepository documentRepository;
    private final TransportOfferRepository offerRepository;
    private final SchoolService schoolService;
    private final VehicleColourService vehicleColourService;
    private final FileStorage fileStorage;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final Clock clock;

    // Police clearance certificates older than this are not accepted
    @Value("${app.verification.police-clearance-max-age-days}")
    private int policeClearanceMaxAgeDays;

    // ─── Registration ────────────────────────────────────────────────────────

    /** An empty profile at sign-up; identity details arrive in onboarding and the verification checklist. */
    @Transactional
    public DriverProfile createProfile(User user) {
        return driverProfileRepository.save(DriverProfile.builder().user(user).build());
    }

    // ─── Driver: own profile ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DriverProfileResponse getMyProfile(User user) {
        return toResponse(requireProfile(user), false);
    }

    @Transactional
    public DriverProfileResponse updateMyProfile(User user, DriverProfileRequest request) {
        DriverProfile driver = requireProfile(user);
        boolean identityLocked = driver.getStatus() == DriverStatus.APPROVED
                || driver.getStatus() == DriverStatus.UNDER_REVIEW;

        if (identityLocked && (changed(request.omangNumber(), driver.getOmangNumber())
                || changed(request.licenceNumber(), driver.getLicenceNumber())
                || changed(request.dateOfBirth(), driver.getDateOfBirth()))) {
            throw BusinessException.conflict("Identity details can't be changed after submission. Contact support.");
        }

        if (request.omangNumber() != null) driver.setOmangNumber(request.omangNumber());
        if (request.licenceNumber() != null) driver.setLicenceNumber(request.licenceNumber().trim().toUpperCase());
        if (request.dateOfBirth() != null) driver.setDateOfBirth(request.dateOfBirth());
        if (request.licenceCode() != null) driver.setLicenceCode(request.licenceCode().trim().toUpperCase());
        if (request.licenceExpiry() != null) driver.setLicenceExpiry(request.licenceExpiry());
        if (request.homeArea() != null) driver.setHomeArea(request.homeArea());
        if (request.acceptsAdHoc() != null) driver.setAcceptsAdHoc(request.acceptsAdHoc());

        boolean payoutChanged = (request.payoutMethod() != null && request.payoutMethod() != driver.getPayoutMethod())
                || changed(request.payoutAccount(), driver.getPayoutAccount());
        if (payoutChanged && !driver.ownsBusiness()) {
            throw BusinessException.badRequest("Payouts for " + driver.getOperator().getName() + " go to "
                    + driver.getOperator().getOwner().getUser().getFirstName() + "'s account");
        }
        if (request.payoutMethod() != null) driver.setPayoutMethod(request.payoutMethod());
        if (request.payoutAccount() != null) driver.setPayoutAccount(request.payoutAccount().trim());
        if (payoutChanged) {
            // Redirecting payouts is the first thing an account thief does — make it visible
            auditService.record(user, "DRIVER_PAYOUT_CHANGED", "DriverProfile", driver.getId(), null);
            notificationService.notify(user, "SECURITY", "Payout details changed",
                    "Your payout account was changed. If this wasn't you, contact support immediately.", driver.getId());
        }
        return toResponse(driver, false);
    }

    @Transactional
    public DriverProfileResponse setMySchools(User user, Set<UUID> schoolIds) {
        DriverProfile me = requireRouteManager(user);
        // A team serves its schools together; the owner's profile holds them
        DriverProfile driver = me.business();
        Set<School> schools = new HashSet<>();
        for (UUID id : schoolIds) {
            schools.add(schoolService.requireVerified(id));
        }
        List<TransportOffer> routes = driver.isInTeam()
                ? offerRepository.findByOperatorId(driver.getOperator().getId())
                : offerRepository.findByDriverId(driver.getId());
        boolean dropsSchoolWithOffers = routes.stream()
                .filter(TransportOffer::isActive)
                .anyMatch(o -> !schoolIds.contains(o.getSchool().getId()));
        if (dropsSchoolWithOffers) {
            throw BusinessException.conflict("Deactivate your offers at a school before removing it");
        }
        driver.getSchools().clear();
        driver.getSchools().addAll(schools);
        return toResponse(me, false);
    }

    // ─── Driver: vehicles ────────────────────────────────────────────────────

    @Transactional
    public VehicleResponse addVehicle(User user, VehicleRequest request) {
        DriverProfile driver = requireRouteManager(user);
        String plate = normalizePlate(request.plateNumber());
        if (vehicleRepository.existsByPlateNumber(plate)) {
            throw BusinessException.conflict("A vehicle with this plate number is already registered");
        }
        // In a team it's a fleet vehicle: any of the team's drivers can be assigned to it
        Vehicle vehicle = Vehicle.builder().driver(driver).operator(driver.getOperator()).plateNumber(plate).build();
        applyVehicle(vehicle, request);
        vehicle = vehicleRepository.save(vehicle);
        return VehicleResponse.from(vehicle, false);
    }

    @Transactional
    public VehicleResponse updateVehicle(User user, UUID vehicleId, VehicleRequest request) {
        DriverProfile driver = requireRouteManager(user);
        Vehicle vehicle = requireVehicle(driver, vehicleId);
        if (!normalizePlate(request.plateNumber()).equals(vehicle.getPlateNumber())) {
            throw BusinessException.badRequest("The plate number can't be changed — add a new vehicle instead");
        }
        int maxOfferCapacity = offerRepository.findByVehicleId(vehicleId).stream()
                .filter(TransportOffer::isActive)
                .mapToInt(TransportOffer::getCapacity).max().orElse(0);
        if (request.seatCapacity() < maxOfferCapacity) {
            throw BusinessException.conflict("An active offer uses " + maxOfferCapacity + " seats on this vehicle");
        }
        applyVehicle(vehicle, request);
        return VehicleResponse.from(vehicle, isVehicleVerified(vehicle));
    }

    @Transactional
    public VehicleResponse deactivateVehicle(User user, UUID vehicleId) {
        DriverProfile driver = requireRouteManager(user);
        Vehicle vehicle = requireVehicle(driver, vehicleId);
        boolean inUse = offerRepository.findByVehicleId(vehicleId).stream().anyMatch(TransportOffer::isActive);
        if (inUse) {
            throw BusinessException.conflict("Deactivate the offers that use this vehicle first");
        }
        vehicle.setActive(false);
        return VehicleResponse.from(vehicle, false);
    }

    // ─── Driver: documents ───────────────────────────────────────────────────

    @Transactional
    public DocumentResponse uploadDocument(User user, DocumentType type, UUID vehicleId, MultipartFile file,
                                           String referenceNumber, LocalDate issuedOn, LocalDate expiresOn) {
        DriverProfile driver = requireProfile(user);
        LocalDate today = LocalDate.now(clock);

        Vehicle vehicle = null;
        if (type.isVehicleDocument()) {
            if (vehicleId == null) {
                throw BusinessException.badRequest("vehicleId is required for " + type);
            }
            if (!driver.managesRoutes()) {
                throw new UnauthorizedException("Your team's owner or manager looks after the vehicles");
            }
            vehicle = requireVehicle(driver, vehicleId);
            // Vehicle papers belong with the vehicle (whoever in the team uploads them)
            driver = vehicle.getDriver();
        } else if (vehicleId != null) {
            throw BusinessException.badRequest(type + " is not a vehicle document");
        }
        if (type == DocumentType.COMPANY_REGISTRATION && !isCompanyOwner(driver)) {
            throw BusinessException.badRequest("Only a transport company's owner uploads the company registration");
        }

        if (expiresOn != null && !expiresOn.isAfter(today)) {
            throw BusinessException.badRequest("This document has already expired");
        }
        switch (type) {
            case POLICE_CLEARANCE -> {
                if (issuedOn == null) {
                    throw BusinessException.badRequest("Enter the date the police clearance was issued");
                }
                if (issuedOn.isBefore(today.minusDays(policeClearanceMaxAgeDays)) || issuedOn.isAfter(today)) {
                    throw BusinessException.badRequest(
                            "Police clearance must be issued within the last " + policeClearanceMaxAgeDays + " days");
                }
                if (referenceNumber == null || referenceNumber.isBlank()) {
                    throw BusinessException.badRequest("Enter the certificate number from the police clearance");
                }
                // Re-checked yearly even if the certificate itself has no expiry
                if (expiresOn == null) {
                    expiresOn = issuedOn.plusYears(1);
                }
            }
            case DRIVERS_LICENCE, ROADWORTHY_CERTIFICATE, INSURANCE, PSV_PERMIT -> {
                if (expiresOn == null) {
                    throw BusinessException.badRequest("Enter the expiry date for " + type);
                }
            }
            default -> {
            }
        }

        UploadValidator.DetectedType detected = UploadValidator.detect(file);
        String key;
        try (InputStream in = file.getInputStream()) {
            key = fileStorage.store("documents/" + driver.getId(), detected.extension(), in, file.getSize(),
                    detected.contentType());
        } catch (IOException e) {
            throw new IllegalStateException("Could not store the uploaded file", e);
        }

        // A newer upload supersedes an unreviewed/rejected one of the same kind; approved ones are kept as history
        VerificationDocument previous = latestDocuments(driver.getId()).get(docKey(type, vehicleId));
        if (previous != null && (previous.getStatus() == DocumentStatus.PENDING
                || previous.getStatus() == DocumentStatus.REJECTED)) {
            documentRepository.delete(previous);
            deleteFileQuietly(previous.getStorageKey());
        }

        VerificationDocument document = documentRepository.save(VerificationDocument.builder()
                .driver(driver)
                .vehicle(vehicle)
                .type(type)
                .storageKey(key)
                .contentType(detected.contentType())
                .sizeBytes(file.getSize())
                .originalFilename(file.getOriginalFilename())
                .referenceNumber(referenceNumber != null ? referenceNumber.trim() : null)
                .issuedOn(issuedOn)
                .expiresOn(expiresOn)
                .build());

        if (type == DocumentType.DRIVERS_LICENCE) {
            driver.setLicenceExpiry(expiresOn);
        }
        if (vehicle != null && type == DocumentType.ROADWORTHY_CERTIFICATE) {
            vehicle.setRoadworthyExpiry(expiresOn);
        }
        if (vehicle != null && type == DocumentType.INSURANCE) {
            vehicle.setInsuranceExpiry(expiresOn);
        }

        // Renewals from an approved driver still need a verifier to look at them
        if (driver.getStatus() == DriverStatus.APPROVED || (vehicle != null && vehicle.getDriver().getStatus() == DriverStatus.APPROVED)) {
            notificationService.notifyStaff(StaffPermission.VERIFIER, "DOCUMENT_REVIEW", "Document renewal to review",
                    user.getFullName() + " uploaded a new " + type + ".", driver.getId());
        }
        return DocumentResponse.from(document);
    }

    @Transactional(readOnly = true)
    public StoredFile downloadMyDocument(User user, UUID documentId) {
        DriverProfile driver = requireProfile(user);
        VerificationDocument document = documentRepository.findById(documentId)
                .filter(d -> d.getDriver().getId().equals(driver.getId())
                        // Fleet vehicle papers: the team's owner and managers
                        || (d.getVehicle() != null && driver.managesRoutes() && driver.isInTeam()
                            && d.getVehicle().getOperator() != null
                            && d.getVehicle().getOperator().getId().equals(driver.getOperator().getId())))
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        return load(document);
    }

    /** Sends the profile to the verification queue once everything required is in. */
    @Transactional
    public DriverProfileResponse submitForReview(User user) {
        DriverProfile driver = requireProfile(user);
        if (driver.getStatus() != DriverStatus.PENDING_DOCUMENTS && driver.getStatus() != DriverStatus.REJECTED) {
            throw BusinessException.conflict("Your profile is already " + driver.getStatus());
        }
        List<String> missing = missingForSubmission(driver);
        if (!missing.isEmpty()) {
            throw BusinessException.badRequest("Still needed: " + String.join("; ", missing));
        }
        driver.setStatus(DriverStatus.UNDER_REVIEW);
        driver.setStatusReason(null);
        driver.setSubmittedAt(Instant.now(clock));
        auditService.record(user, "DRIVER_SUBMITTED", "DriverProfile", driver.getId(), null);
        notificationService.notifyStaff(StaffPermission.VERIFIER, "DRIVER_REVIEW", "New driver to verify",
                user.getFullName() + " submitted their documents for review.", driver.getId());
        return toResponse(driver, false);
    }

    // ─── Admin: verification ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<DriverSummaryResponse> adminSearch(DriverStatus status, String query, Pageable pageable) {
        String q = query == null || query.isBlank() ? null : query.trim();
        return PageResponse.from(driverProfileRepository.search(status, q, pageable).map(DriverSummaryResponse::from));
    }

    @Transactional
    public DriverProfileResponse adminGet(User admin, UUID driverId) {
        DriverProfile driver = requireDriver(driverId);
        // Identity numbers are shown in full here, so record who looked
        auditService.record(admin, "DRIVER_VIEWED", "DriverProfile", driverId, null);
        return toResponse(driver, true);
    }

    @Transactional
    public DocumentResponse reviewDocument(User admin, UUID documentId, DocumentReviewRequest request) {
        VerificationDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        boolean approve = request.approve();
        if (!approve && (request.note() == null || request.note().isBlank())) {
            throw BusinessException.badRequest("Say why the document is rejected so the driver can fix it");
        }
        if (approve && document.getExpiresOn() != null && !document.getExpiresOn().isAfter(LocalDate.now(clock))) {
            throw BusinessException.badRequest("This document has expired");
        }
        document.setStatus(approve ? DocumentStatus.APPROVED : DocumentStatus.REJECTED);
        document.setReviewNote(request.note());
        document.setReviewedBy(admin);
        document.setReviewedAt(Instant.now(clock));
        auditService.record(admin, approve ? "DOCUMENT_APPROVED" : "DOCUMENT_REJECTED", "VerificationDocument",
                document.getId(), document.getType() + (request.note() != null ? ": " + request.note() : ""));

        if (!approve) {
            notificationService.notify(document.getDriver().getUser(), "DOCUMENT", "Document rejected",
                    "Your " + document.getType() + " was rejected: " + request.note() + ". Please upload a new one.",
                    document.getId());
        }
        return DocumentResponse.from(document);
    }

    @Transactional
    public DriverProfileResponse approve(User admin, UUID driverId) {
        DriverProfile driver = requireDriver(driverId);
        if (driver.getStatus() != DriverStatus.UNDER_REVIEW && driver.getStatus() != DriverStatus.SUSPENDED) {
            throw BusinessException.conflict("Only drivers under review or suspended can be approved");
        }
        List<String> missing = missingForApproval(driver);
        if (!missing.isEmpty()) {
            throw BusinessException.badRequest("Can't approve yet: " + String.join("; ", missing));
        }
        setStatus(admin, driver, DriverStatus.APPROVED, null);
        notificationService.notify(driver.getUser(), "DRIVER_STATUS", "You're approved!",
                "You are now a verified SchoolDrop driver. Create an offer so parents can book you.", driver.getId());
        return toResponse(driver, true);
    }

    @Transactional
    public DriverProfileResponse reject(User admin, UUID driverId, String reason) {
        DriverProfile driver = requireDriver(driverId);
        if (driver.getStatus() != DriverStatus.UNDER_REVIEW) {
            throw BusinessException.conflict("Only drivers under review can be rejected");
        }
        setStatus(admin, driver, DriverStatus.REJECTED, reason);
        notificationService.notify(driver.getUser(), "DRIVER_STATUS", "Verification unsuccessful",
                "Your application was not approved: " + reason + ". You can fix this and resubmit.", driver.getId());
        return toResponse(driver, true);
    }

    /** Immediately stops the driver from taking new bookings or running trips. */
    @Transactional
    public DriverProfileResponse suspend(User admin, UUID driverId, String reason) {
        DriverProfile driver = requireDriver(driverId);
        setStatus(admin, driver, DriverStatus.SUSPENDED, reason);
        int offers = offerRepository.deactivateAllForDriver(driver.getId());
        if (driver.isInTeam() && driver.getOperatorRole() == OperatorRole.OWNER) {
            // Suspending the business stops all of its routes
            List<TransportOffer> teamRoutes = offerRepository.findByOperatorId(driver.getOperator().getId()).stream()
                    .filter(TransportOffer::isActive).toList();
            teamRoutes.forEach(o -> o.setActive(false));
            offers += teamRoutes.size();
        }
        notificationService.notify(driver.getUser(), "DRIVER_STATUS", "Account suspended",
                "Your driver account was suspended: " + reason, driver.getId());
        log.warn("Driver {} suspended by {}; {} offers deactivated", driver.getId(), admin.getId(), offers);
        return toResponse(driver, true);
    }

    @Transactional(readOnly = true)
    public StoredFile adminDownloadDocument(UUID documentId) {
        return load(documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found")));
    }

    /**
     * Called by the daily expiry job: an approved driver whose required documents lapsed goes back to
     * PENDING_DOCUMENTS and their offers stop.
     */
    @Transactional
    public void handleExpiredDocuments(DriverProfile driver) {
        if (driver.getStatus() != DriverStatus.APPROVED) {
            return;
        }
        List<String> missing = missingForApproval(driver);
        if (missing.isEmpty()) {
            return;
        }
        driver.setStatus(DriverStatus.PENDING_DOCUMENTS);
        driver.setStatusReason("Expired: " + String.join("; ", missing));
        offerRepository.deactivateAllForDriver(driver.getId());
        auditService.recordSystem("DRIVER_DOCUMENTS_EXPIRED", "DriverProfile", driver.getId(), driver.getStatusReason());
        notificationService.notify(driver.getUser(), "DRIVER_STATUS", "Documents expired",
                "Your offers are paused until you upload valid documents: " + String.join("; ", missing),
                driver.getId());
    }

    /** Nightly: lapse expired documents, pause affected drivers, warn about upcoming expiries. */
    @Transactional
    public void expireDocuments() {
        LocalDate today = LocalDate.now(clock);
        List<VerificationDocument> expired = documentRepository.findByStatusAndExpiresOnBefore(DocumentStatus.APPROVED, today);
        expired.forEach(d -> d.setStatus(DocumentStatus.EXPIRED));
        expired.stream().map(VerificationDocument::getDriver).distinct().forEach(this::handleExpiredDocuments);
        // A vehicle whose papers lapsed stops running its routes, even if the business has other good vehicles
        expired.stream().map(VerificationDocument::getVehicle).filter(Objects::nonNull).distinct().forEach(vehicle -> {
            List<TransportOffer> paused = offerRepository.findByVehicleId(vehicle.getId()).stream()
                    .filter(TransportOffer::isActive).toList();
            paused.forEach(o -> o.setActive(false));
            if (!paused.isEmpty()) {
                notificationService.notify(vehicle.getDriver().business().getUser(), "DRIVER_STATUS", "Vehicle papers expired",
                        vehicle.getPlateNumber() + "'s papers expired, so " + paused.size()
                                + " route(s) using it are paused. Upload renewed papers, or move the routes to another vehicle.",
                        vehicle.getId());
            }
        });

        documentRepository.findByStatusAndExpiresOn(DocumentStatus.APPROVED, today.plusDays(EXPIRY_WARNING_DAYS))
                .forEach(d -> notificationService.notify(d.getDriver().getUser(), "DOCUMENT", "Document expiring soon",
                        "Your " + label(d.getType()) + " expires on " + d.getExpiresOn()
                                + ". Upload a renewed one to keep taking bookings.", d.getId()));
        log.info("Expired {} documents", expired.size());
    }

    // ─── Shared helpers ──────────────────────────────────────────────────────

    public DriverProfile requireProfile(User user) {
        return driverProfileRepository.findByUserId(user.getId())
                .orElseThrow(() -> new UnauthorizedException("No driver profile for this account"));
    }

    public DriverProfile requireApproved(User user) {
        DriverProfile driver = requireProfile(user);
        if (!driver.isApproved()) {
            throw new UnauthorizedException("Your driver account is not approved");
        }
        return driver;
    }

    public DriverProfile requireDriver(UUID driverId) {
        return driverProfileRepository.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found"));
    }

    /** True when every required vehicle document's latest version is approved and in date. */
    @Transactional(readOnly = true)
    public boolean isVehicleVerified(Vehicle vehicle) {
        return isVehicleVerified(vehicle, latestDocuments(vehicle.getDriver().getId()));
    }

    private boolean isVehicleVerified(Vehicle vehicle, Map<String, VerificationDocument> latest) {
        return DocumentType.REQUIRED_FOR_VEHICLE.stream().allMatch(type -> {
            VerificationDocument doc = latest.get(docKey(type, vehicle.getId()));
            return doc != null && doc.getStatus() == DocumentStatus.APPROVED;
        });
    }

    /**
     * What each person must provide, by what they do:
     * <ul>
     *   <li>everyone: Omang (both sides), selfie, police clearance — they see children's names and addresses</li>
     *   <li>anyone who drives: licence</li>
     *   <li>the business (a driver on their own, or a team owner): payout account, schools, at least one vehicle</li>
     *   <li>a transport company's owner: the CIPA registration certificate</li>
     * </ul>
     * Team drivers and managers don't need their own vehicle or payout account.
     */
    private Set<DocumentType> requiredDocuments(DriverProfile driver) {
        Set<DocumentType> required = EnumSet.of(DocumentType.OMANG_FRONT, DocumentType.OMANG_BACK, DocumentType.SELFIE,
                DocumentType.POLICE_CLEARANCE);
        if (driver.isDrives()) {
            required.add(DocumentType.DRIVERS_LICENCE);
        }
        if (isCompanyOwner(driver)) {
            required.add(DocumentType.COMPANY_REGISTRATION);
        }
        return required;
    }

    private static boolean isCompanyOwner(DriverProfile driver) {
        return driver.isInTeam() && driver.getOperatorRole() == OperatorRole.OWNER
                && driver.getOperator().getKind() == OperatorKind.COMPANY;
    }

    /** The vehicles this person's business runs: a team's fleet, or a solo driver's own. */
    public List<Vehicle> businessVehicles(DriverProfile driver) {
        return driver.isInTeam()
                ? vehicleRepository.findByOperatorIdOrderByCreatedAt(driver.getOperator().getId())
                : vehicleRepository.findByDriverIdOrderByCreatedAt(driver.getId());
    }

    private List<String> missingForSubmission(DriverProfile driver) {
        List<String> missing = new ArrayList<>(missingProfileFields(driver));
        Map<String, VerificationDocument> latest = latestDocuments(driver.getId());
        for (DocumentType type : requiredDocuments(driver)) {
            VerificationDocument doc = latest.get(docKey(type, null));
            if (doc == null || doc.getStatus() == DocumentStatus.REJECTED || doc.getStatus() == DocumentStatus.EXPIRED) {
                missing.add(label(type));
            }
        }
        if (driver.ownsBusiness()) {
            List<Vehicle> vehicles = businessVehicles(driver).stream().filter(Vehicle::isActive).toList();
            if (vehicles.isEmpty()) {
                missing.add("at least one vehicle");
            }
            Map<UUID, Map<String, VerificationDocument>> byRegistrant = new HashMap<>();
            for (Vehicle vehicle : vehicles) {
                Map<String, VerificationDocument> docs = byRegistrant.computeIfAbsent(vehicle.getDriver().getId(), this::latestDocuments);
                for (DocumentType type : DocumentType.REQUIRED_FOR_VEHICLE) {
                    VerificationDocument doc = docs.get(docKey(type, vehicle.getId()));
                    if (doc == null || doc.getStatus() == DocumentStatus.REJECTED || doc.getStatus() == DocumentStatus.EXPIRED) {
                        missing.add(label(type) + " for " + vehicle.getPlateNumber());
                    }
                }
            }
            if (driver.getSchools().isEmpty()) {
                missing.add("at least one school you serve");
            }
        }
        return missing;
    }

    private List<String> missingForApproval(DriverProfile driver) {
        List<String> missing = new ArrayList<>(missingProfileFields(driver));
        Map<String, VerificationDocument> latest = latestDocuments(driver.getId());
        for (DocumentType type : requiredDocuments(driver)) {
            VerificationDocument doc = latest.get(docKey(type, null));
            if (doc == null || doc.getStatus() != DocumentStatus.APPROVED) {
                missing.add(label(type) + " (approved)");
            }
        }
        if (driver.ownsBusiness()) {
            boolean anyVehicleVerified = businessVehicles(driver).stream().filter(Vehicle::isActive)
                    .anyMatch(this::isVehicleVerified);
            if (!anyVehicleVerified) {
                missing.add("a vehicle with all documents approved");
            }
        }
        if (driver.isDrives() && driver.getLicenceExpiry() != null && !driver.getLicenceExpiry().isAfter(LocalDate.now(clock))) {
            missing.add("a driver's licence that hasn't expired");
        }
        return missing;
    }

    private List<String> missingProfileFields(DriverProfile driver) {
        List<String> missing = new ArrayList<>();
        if (driver.getOmangNumber() == null) missing.add("Omang number");
        if (driver.getDateOfBirth() == null) missing.add("date of birth");
        if (driver.isDrives()) {
            if (driver.getLicenceNumber() == null) missing.add("licence number");
            if (driver.getLicenceCode() == null) missing.add("licence code");
        }
        if (driver.ownsBusiness() && (driver.getPayoutMethod() == null || driver.getPayoutAccount() == null)) {
            missing.add("payout account");
        }
        return missing;
    }

    /** Latest document per (type, vehicle). */
    private Map<String, VerificationDocument> latestDocuments(UUID driverId) {
        Map<String, VerificationDocument> latest = new HashMap<>();
        // findByDriverId is ordered newest first, so the first one seen wins
        for (VerificationDocument doc : documentRepository.findByDriverId(driverId)) {
            latest.putIfAbsent(docKey(doc.getType(), doc.getVehicle() != null ? doc.getVehicle().getId() : null), doc);
        }
        return latest;
    }

    private static String docKey(DocumentType type, UUID vehicleId) {
        return type + ":" + vehicleId;
    }

    private DriverProfileResponse toResponse(DriverProfile driver, boolean revealIdentity) {
        // A team member sees the team's fleet; the owner / managers manage it
        List<Vehicle> fleet = businessVehicles(driver);
        List<VehicleResponse> vehicles = fleet.stream().map(v -> VehicleResponse.from(v, isVehicleVerified(v))).toList();
        List<DocumentResponse> documents = new ArrayList<>(documentRepository.findByDriverId(driver.getId()).stream()
                .filter(d -> d.getVehicle() == null || !driver.isInTeam())
                .map(DocumentResponse::from)
                .toList());
        if (driver.isInTeam() && driver.managesRoutes()) {
            Set<UUID> fleetIds = new HashSet<>();
            fleet.forEach(v -> fleetIds.add(v.getId()));
            fleet.stream().map(v -> v.getDriver().getId()).distinct()
                    .flatMap(registrant -> documentRepository.findByDriverId(registrant).stream())
                    .filter(d -> d.getVehicle() != null && fleetIds.contains(d.getVehicle().getId()))
                    .map(DocumentResponse::from)
                    .forEach(documents::add);
        }
        List<String> missing = driver.getStatus() == DriverStatus.APPROVED || driver.getStatus() == DriverStatus.UNDER_REVIEW
                ? missingForApproval(driver)
                : missingForSubmission(driver);
        return DriverProfileResponse.from(driver, vehicles, documents, missing, revealIdentity);
    }

    private void setStatus(User admin, DriverProfile driver, DriverStatus status, String reason) {
        if (status != DriverStatus.APPROVED && (reason == null || reason.isBlank())) {
            throw BusinessException.badRequest("A reason is required");
        }
        driver.setStatus(status);
        driver.setStatusReason(reason);
        driver.setReviewedBy(admin);
        driver.setReviewedAt(Instant.now(clock));
        auditService.record(admin, "DRIVER_" + status, "DriverProfile", driver.getId(), reason);
    }

    /** A vehicle this person's business runs: their own, or (team owner / manager) any fleet vehicle. */
    public Vehicle requireVehicle(DriverProfile driver, UUID vehicleId) {
        return vehicleRepository.findById(vehicleId)
                .filter(v -> driver.isInTeam()
                        ? v.getOperator() != null && v.getOperator().getId().equals(driver.getOperator().getId())
                        : v.getDriver().getId().equals(driver.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found"));
    }

    /** Owners and managers of a team, or a driver working on their own. */
    public DriverProfile requireRouteManager(User user) {
        DriverProfile driver = requireProfile(user);
        if (!driver.managesRoutes()) {
            throw new UnauthorizedException("Your team's owner or manager looks after vehicles and routes");
        }
        return driver;
    }

    private void applyVehicle(Vehicle vehicle, VehicleRequest request) {
        if (request.year() != null && request.year() > LocalDate.now(clock).getYear() + 1) {
            throw BusinessException.badRequest("Choose the vehicle's year from the list");
        }
        vehicle.setMake(request.make().trim());
        vehicle.setModel(request.model().trim());
        vehicle.setYear(request.year());
        vehicle.setColour(vehicleColourService.requireListed(request.colour()));
        vehicle.setType(request.type());
        vehicle.setSeatCapacity(request.seatCapacity());
        if (request.roadworthyExpiry() != null) vehicle.setRoadworthyExpiry(request.roadworthyExpiry());
        if (request.insuranceExpiry() != null) vehicle.setInsuranceExpiry(request.insuranceExpiry());
    }

    private static String normalizePlate(String plate) {
        return plate.replaceAll("\\s+", "").toUpperCase();
    }

    private static boolean changed(Object requested, Object current) {
        return requested != null && !requested.equals(current);
    }

    private static String label(DocumentType type) {
        return switch (type) {
            case OMANG_FRONT -> "Omang (front)";
            case OMANG_BACK -> "Omang (back)";
            case DRIVERS_LICENCE -> "driver's licence";
            case POLICE_CLEARANCE -> "police clearance certificate";
            case SELFIE -> "selfie";
            case PSV_PERMIT -> "PSV permit";
            case VEHICLE_REGISTRATION -> "vehicle registration";
            case ROADWORTHY_CERTIFICATE -> "roadworthy certificate";
            case INSURANCE -> "insurance";
            case VEHICLE_PHOTO -> "vehicle photo";
            case COMPANY_REGISTRATION -> "company registration (CIPA) certificate";
        };
    }

    private StoredFile load(VerificationDocument document) {
        try {
            return new StoredFile(fileStorage.load(document.getStorageKey()), document.getContentType(),
                    document.getType().name().toLowerCase() + "-" + document.getId() + extension(document.getContentType()));
        } catch (IOException e) {
            throw new ResourceNotFoundException("The file for this document is missing");
        }
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            default -> ".jpg";
        };
    }

    private void deleteFileQuietly(String key) {
        try {
            fileStorage.delete(key);
        } catch (IOException e) {
            log.warn("Could not delete superseded file {}: {}", key, e.getMessage());
        }
    }

    public record StoredFile(Resource resource, String contentType, String filename) {
    }
}
