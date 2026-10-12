package com.gimble.bus_stop.config;

import com.gimble.bus_stop.entities.*;
import com.gimble.bus_stop.enums.*;
import com.gimble.bus_stop.repositories.*;
import com.gimble.bus_stop.security.util.TokenUtils;
import com.gimble.bus_stop.services.BookingPricing;
import com.gimble.bus_stop.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;

/**
 * Local development only (app.seed-demo-data=true, the local profile's default): on an empty database,
 * creates one of everything so every side of the app can be tried immediately. All demo accounts share
 * the password below.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "app.seed-demo-data", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DemoDataSeeder implements ApplicationRunner {

    static final String PASSWORD = "Demo-Pass-123";

    private final UserRepository userRepository;
    private final SchoolRepository schoolRepository;
    private final SchoolStaffRepository schoolStaffRepository;
    private final ChildRepository childRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final VehicleRepository vehicleRepository;
    private final VerificationDocumentRepository documentRepository;
    private final TransportOfferRepository offerRepository;
    private final SchoolTermRepository termRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final HouseholdRepository householdRepository;
    private final HouseholdMemberRepository householdMemberRepository;
    private final FileStorage fileStorage;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Value("${app.platform.fee-percent}")
    private BigDecimal feePercent;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (schoolRepository.count() > 0) {
            return;
        }
        LocalDate today = LocalDate.now(clock);

        // School
        School school = schoolRepository.save(School.builder()
                .name("Demo Primary School")
                .registrationNumber("MOE-DEMO-001")
                .address("Plot 1234, Independence Ave")
                .city("Gaborone")
                .latitude(-24.6545)
                .longitude(25.9086)
                .phone("+2673900000")
                .status(SchoolStatus.VERIFIED)
                .reviewedAt(Instant.now(clock))
                .build());
        User schoolUser = user("school@schooldrop.local", "Mpho", "Office", "+26771000001", Role.SCHOOL);
        schoolStaffRepository.save(SchoolStaff.builder().school(school).user(schoolUser).role(SchoolRole.ADMIN).build());
        User teacher = user("teacher@schooldrop.local", "Kefilwe", "Dube", "+26771000004", Role.SCHOOL);
        schoolStaffRepository.save(SchoolStaff.builder().school(school).user(teacher).role(SchoolRole.STAFF)
                .title("Grade 3 teacher").build());

        termRepository.save(SchoolTerm.builder()
                .name("Demo term " + today.getYear())
                .startDate(today.minusWeeks(4))
                .endDate(today.plusWeeks(8))
                .build());

        // Approved driver with a verified vehicle and an offer at the school
        User driverUser = user("driver@schooldrop.local", "Kabo", "Molefe", "+26772000002", Role.DRIVER);
        DriverProfile driver = driverProfileRepository.save(DriverProfile.builder()
                .user(driverUser)
                .status(DriverStatus.APPROVED)
                .omangNumber("123456789")
                .licenceNumber("BW1234567")
                .licenceCode("C1")
                .licenceExpiry(today.plusYears(3))
                .dateOfBirth(LocalDate.of(1986, 3, 14))
                .homeArea("Phakalane")
                .acceptsAdHoc(true)
                .payoutMethod(PaymentMethod.ORANGE_MONEY)
                .payoutAccount("+26772000002")
                .schools(new HashSet<>(Set.of(school)))
                .submittedAt(Instant.now(clock))
                .reviewedAt(Instant.now(clock))
                .subscriptionPaidUntil(today.plusMonths(1))
                .build());
        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .driver(driver)
                .make("Toyota")
                .model("Quantum")
                .year(2020)
                .colour("White")
                .plateNumber("B123DEM")
                .type(VehicleType.MINIBUS)
                .seatCapacity(14)
                .roadworthyExpiry(today.plusMonths(10))
                .insuranceExpiry(today.plusMonths(10))
                .build());
        for (DocumentType type : DocumentType.REQUIRED_FOR_DRIVER) {
            document(driver, null, type, today);
        }
        for (DocumentType type : DocumentType.REQUIRED_FOR_VEHICLE) {
            document(driver, vehicle, type, today);
        }
        TransportOffer offer = offerRepository.save(TransportOffer.builder()
                .driver(driver)
                .vehicle(vehicle)
                .school(school)
                .coverage(TripCoverage.BOTH)
                .capacity(10)
                .serviceArea("Phakalane, Block 10, Broadhurst")
                .morningPickupTime("06:45")
                .pricePerTrip(new BigDecimal("30.00"))
                .pricePerMonth(new BigDecimal("650.00"))
                .pricePerTerm(new BigDecimal("1800.00"))
                .adHocFare(new BigDecimal("90.00"))
                .build());

        // Parent with two children at the school; one already rides with the driver this month
        User parent = user("parent@schooldrop.local", "Naledi", "Kgosi", "+26773000003", Role.PARENT);
        Household family = householdRepository.save(Household.builder().name("Kgosi family").owner(parent).build());
        householdMemberRepository.save(HouseholdMember.builder().household(family).user(parent).role(HouseholdRole.OWNER).build());
        User helper = user("helper@schooldrop.local", "Mpho", "Sello", "+26773000005", Role.PARENT);
        householdMemberRepository.save(HouseholdMember.builder().household(family).user(helper).role(HouseholdRole.HELPER).build());
        Child neo = child(parent, school, "Neo", "Std 3", "Peanut allergy");
        child(parent, school, "Lesedi", "Std 1", null);

        YearMonth month = YearMonth.from(today);
        Booking booking = bookingRepository.save(Booking.builder()
                .child(neo)
                .parent(parent)
                .offer(offer)
                .plan(BillingPlan.MONTHLY)
                .startDate(month.atDay(1))
                .endDate(month.atEndOfMonth())
                .pickupAddress("Plot 5521, Phakalane")
                .amount(offer.getPricePerMonth())
                .status(BookingStatus.ACTIVE)
                .build());
        BigDecimal fee = BookingPricing.platformFee(booking.getAmount(), feePercent);
        paymentRepository.save(Payment.builder()
                .payer(parent)
                .driver(driver)
                .booking(booking)
                .amount(booking.getAmount())
                .platformFee(fee)
                .driverAmount(booking.getAmount().subtract(fee))
                .method(PaymentMethod.ORANGE_MONEY)
                .status(PaymentStatus.SUCCEEDED)
                .providerReference("DEMO-" + TokenUtils.randomCode(8))
                .earningAvailableOn(booking.getStartDate())
                .paidAt(Instant.now(clock))
                .build());

        log.warn("Demo data created. Sign in with parent@ / helper@ / driver@ / school@ / teacher@schooldrop.local, password {}",
                PASSWORD);
    }

    private User user(String email, String first, String last, String phone, Role role) {
        return userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode(PASSWORD))
                .firstName(first)
                .lastName(last)
                .phone(phone)
                .role(role)
                .emailVerified(true)
                .onboardedAt(Instant.now(clock))
                .build());
    }

    private Child child(User parent, School school, String firstName, String grade, String specialNeeds) {
        return childRepository.save(Child.builder()
                .parent(parent)
                .school(school)
                .firstName(firstName)
                .lastName(parent.getLastName())
                .grade(grade)
                .specialNeeds(specialNeeds)
                .enrollmentStatus(EnrollmentStatus.CONFIRMED)
                .build());
    }

    private void document(DriverProfile driver, Vehicle vehicle, DocumentType type, LocalDate today) {
        byte[] placeholder = ("%PDF-1.4\n% SchoolDrop demo placeholder for " + type + "\n").getBytes(StandardCharsets.UTF_8);
        String key;
        try {
            key = fileStorage.store("documents/" + driver.getId(), "pdf", new ByteArrayInputStream(placeholder),
                    placeholder.length, "application/pdf");
        } catch (Exception e) {
            // Storage (MinIO) not running: the record still exists, only the file view will 404
            log.warn("Demo document {} not stored: {}", type, e.getMessage());
            key = "documents/missing/" + type;
        }
        boolean expires = type == DocumentType.DRIVERS_LICENCE || type == DocumentType.ROADWORTHY_CERTIFICATE
                || type == DocumentType.INSURANCE || type == DocumentType.POLICE_CLEARANCE;
        documentRepository.save(VerificationDocument.builder()
                .driver(driver)
                .vehicle(vehicle)
                .type(type)
                .storageKey(key)
                .contentType("application/pdf")
                .sizeBytes(placeholder.length)
                .originalFilename(type.name().toLowerCase() + ".pdf")
                .referenceNumber(type == DocumentType.POLICE_CLEARANCE ? "BPS-DEMO-0001" : null)
                .issuedOn(type == DocumentType.POLICE_CLEARANCE ? today.minusDays(20) : null)
                .expiresOn(expires ? today.plusMonths(10) : null)
                .status(DocumentStatus.APPROVED)
                .reviewedAt(Instant.now(clock))
                .build());
    }
}
