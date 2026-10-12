package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.requests.AdvertRequest;
import com.gimble.bus_stop.dto.requests.PayRequest;
import com.gimble.bus_stop.dto.requests.StaffAdvertRequest;
import com.gimble.bus_stop.dto.responses.AdvertResponse;
import com.gimble.bus_stop.dto.responses.PageResponse;
import com.gimble.bus_stop.dto.responses.PaymentResponse;
import com.gimble.bus_stop.entities.*;
import com.gimble.bus_stop.enums.*;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.exceptions.UnauthorizedException;
import com.gimble.bus_stop.payments.PaymentSucceededEvent;
import com.gimble.bus_stop.repositories.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sponsored cards for parents and school staff. Placement rules (enforced by the apps):
 * one card per screen, clearly labelled "Sponsored", only on calm screens (parent home, children list,
 * school portal) — never on trip tracking, SOS, the gate list, or to kids and drivers.
 * Every advert is reviewed by staff before it goes live.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdvertService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdvertRepository advertRepository;
    private final PaymentRepository paymentRepository;
    private final SchoolRepository schoolRepository;
    private final ChildRepository childRepository;
    private final SchoolStaffRepository schoolStaffRepository;
    private final DriverService driverService;
    private final PaymentService paymentService;
    private final HouseholdService householdService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final Clock clock;

    @Value("${app.adverts.price}")
    private BigDecimal price;

    @Value("${app.adverts.days}")
    private int days;

    // ─── Drivers ─────────────────────────────────────────────────────────────

    @Transactional
    public AdvertResponse create(User user, AdvertRequest request) {
        DriverProfile driver = requireBusiness(user);
        School target = null;
        if (request.targetSchoolId() != null) {
            target = driver.getSchools().stream().filter(s -> s.getId().equals(request.targetSchoolId())).findFirst()
                    .orElseThrow(() -> BusinessException.badRequest("You can only advertise at schools you serve"));
        }
        Advert advert = advertRepository.save(Advert.builder()
                .driver(driver)
                .advertiserName(driver.isInTeam() ? driver.getOperator().getName() : user.getFullName())
                .title(request.title().trim())
                .body(request.body().trim())
                .ctaLabel(request.ctaLabel() != null ? request.ctaLabel() : "Call")
                .ctaPhone(request.ctaPhone() != null ? request.ctaPhone() : user.getPhone())
                .targetSchool(target)
                .status(AdvertStatus.PENDING_PAYMENT)
                .price(price)
                .createdBy(user)
                .build());
        return AdvertResponse.from(advert);
    }

    @Transactional(readOnly = true)
    public List<AdvertResponse> listMine(User user) {
        DriverProfile driver = driverService.requireProfile(user);
        return advertRepository.findByDriverId(driver.getId()).stream().map(AdvertResponse::from).toList();
    }

    @Transactional
    public PaymentResponse pay(User user, UUID advertId, PayRequest request) {
        DriverProfile driver = requireBusiness(user);
        Advert advert = advertRepository.findById(advertId)
                .filter(a -> a.getDriver() != null && a.getDriver().getId().equals(driver.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Advert not found"));
        if (advert.getStatus() != AdvertStatus.PENDING_PAYMENT) {
            throw BusinessException.conflict("This advert is " + advert.getStatus());
        }
        return paymentService.chargeDriver(Payment.builder()
                .payer(user)
                .driver(driver)
                .advert(advert)
                .purpose(PaymentPurpose.ADVERT)
                .earningAvailableOn(LocalDate.now(clock))
                .build(), advert.getPrice(), request);
    }

    @EventListener
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        Advert advert = event.payment().getAdvert();
        if (advert == null || advert.getStatus() != AdvertStatus.PENDING_PAYMENT) {
            return;
        }
        advert.setStatus(AdvertStatus.PENDING_REVIEW);
        notificationService.notifyStaff(StaffPermission.MARKETING, "ADVERT_REVIEW", "Advert to review",
                advert.getAdvertiserName() + ": \"" + advert.getTitle() + "\"", advert.getId());
    }

    // ─── Viewers (parents, school staff) ─────────────────────────────────────

    /** One live advert relevant to the viewer's schools, or empty. Counts an impression. */
    @Transactional
    public Optional<AdvertResponse> next(User viewer) {
        Set<UUID> schoolIds = viewerSchools(viewer);
        Instant now = Instant.now(clock);
        List<Advert> live = schoolIds.isEmpty() ? advertRepository.findLiveUntargeted(now)
                : advertRepository.findLiveFor(schoolIds, now);
        if (live.isEmpty()) {
            return Optional.empty();
        }
        Advert advert = live.get(RANDOM.nextInt(live.size()));
        advertRepository.countImpression(advert.getId());
        return Optional.of(AdvertResponse.forViewer(advert));
    }

    @Transactional
    public void click(UUID advertId) {
        advertRepository.countClick(advertId);
    }

    /** Drivers with a live advert at the school: their offers show as "Featured". */
    @Transactional(readOnly = true)
    /** Adverts are bought by the business: a driver on their own, or a team's owner (featuring the whole team). */
    private DriverProfile requireBusiness(User user) {
        DriverProfile driver = driverService.requireApproved(user);
        if (!driver.ownsBusiness()) {
            throw new UnauthorizedException(driver.getOperator().getOwner().getUser().getFirstName()
                    + " runs adverts for " + driver.getOperator().getName());
        }
        return driver;
    }

    public Set<UUID> featuredDriverIds(UUID schoolId) {
        return new HashSet<>(advertRepository.findFeaturedDriverIds(schoolId, Instant.now(clock)));
    }

    private Set<UUID> viewerSchools(User viewer) {
        return switch (viewer.getRole()) {
            case PARENT -> childRepository.findByParentId(householdService.requireMember(viewer).getId()).stream()
                    .filter(c -> c.getSchool() != null)
                    .map(c -> c.getSchool().getId())
                    .collect(Collectors.toSet());
            case SCHOOL -> schoolStaffRepository.findByUserId(viewer.getId())
                    .map(s -> Set.of(s.getSchool().getId())).orElse(Set.of());
            default -> throw new UnauthorizedException("Adverts are shown to parents and schools only");
        };
    }

    // ─── Staff ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<AdvertResponse> adminSearch(AdvertStatus status, Pageable pageable) {
        return PageResponse.from(advertRepository.adminSearch(status, pageable).map(AdvertResponse::from));
    }

    @Transactional
    public AdvertResponse review(User admin, UUID advertId, boolean approve, String note) {
        Advert advert = require(advertId);
        if (advert.getStatus() != AdvertStatus.PENDING_REVIEW) {
            throw BusinessException.conflict("Only adverts waiting for review can be approved or rejected");
        }
        if (!approve && (note == null || note.isBlank())) {
            throw BusinessException.badRequest("Say why the advert is rejected");
        }
        advert.setReviewedBy(admin);
        advert.setReviewedAt(Instant.now(clock));
        advert.setReviewNote(note);
        if (approve) {
            advert.setStatus(AdvertStatus.ACTIVE);
            advert.setStartsAt(Instant.now(clock));
            advert.setEndsAt(Instant.now(clock).plus(Duration.ofDays(days)));
        } else {
            advert.setStatus(AdvertStatus.REJECTED);
            // Rejected adverts are refunded in full
            paymentRepository.findByAdvertId(advert.getId()).stream()
                    .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED)
                    .forEach(p -> paymentService.refund(admin, p, "Advert not approved: " + note));
        }
        auditService.record(admin, approve ? "ADVERT_APPROVED" : "ADVERT_REJECTED", "Advert", advert.getId(), note);
        if (advert.getDriver() != null) {
            notificationService.notify(advert.getDriver().getUser(), "ADVERT",
                    approve ? "Your advert is live" : "Advert not approved",
                    approve ? "\"" + advert.getTitle() + "\" runs for " + days + " days."
                            : "\"" + advert.getTitle() + "\" wasn't approved: " + note + ". You've been refunded.",
                    advert.getId());
        }
        return AdvertResponse.from(advert);
    }

    @Transactional
    public AdvertResponse createForAdvertiser(User admin, StaffAdvertRequest request) {
        School target = request.targetSchoolId() == null ? null : schoolRepository.findById(request.targetSchoolId())
                .orElseThrow(() -> new ResourceNotFoundException("School not found"));
        Instant now = Instant.now(clock);
        Advert advert = advertRepository.save(Advert.builder()
                .advertiserName(request.advertiserName().trim())
                .title(request.title().trim())
                .body(request.body().trim())
                .ctaLabel(request.ctaLabel())
                .ctaPhone(request.ctaPhone())
                .ctaUrl(request.ctaUrl())
                .targetSchool(target)
                .status(AdvertStatus.ACTIVE)
                .startsAt(now)
                .endsAt(now.plus(Duration.ofDays(request.days())))
                .price(request.pricePaid())
                .reviewedBy(admin)
                .reviewedAt(now)
                .createdBy(admin)
                .build());
        auditService.record(admin, "ADVERT_CREATED", "Advert", advert.getId(), request.advertiserName());
        return AdvertResponse.from(advert);
    }

    @Transactional
    public AdvertResponse setPaused(User admin, UUID advertId, boolean paused) {
        Advert advert = require(advertId);
        if (paused && advert.getStatus() == AdvertStatus.ACTIVE) {
            advert.setStatus(AdvertStatus.PAUSED);
        } else if (!paused && advert.getStatus() == AdvertStatus.PAUSED) {
            advert.setStatus(AdvertStatus.ACTIVE);
        } else {
            throw BusinessException.conflict("This advert is " + advert.getStatus());
        }
        auditService.record(admin, paused ? "ADVERT_PAUSED" : "ADVERT_RESUMED", "Advert", advert.getId(), null);
        return AdvertResponse.from(advert);
    }

    @Transactional
    public int expire() {
        List<Advert> ended = advertRepository.findByStatusAndEndsAtBefore(AdvertStatus.ACTIVE, Instant.now(clock));
        ended.forEach(a -> a.setStatus(AdvertStatus.EXPIRED));
        return ended.size();
    }

    private Advert require(UUID id) {
        return advertRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Advert not found"));
    }
}
