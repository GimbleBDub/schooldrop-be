package com.gimble.bus_stop.services;

import com.gimble.bus_stop.dto.requests.PayRequest;
import com.gimble.bus_stop.dto.responses.PaymentResponse;
import com.gimble.bus_stop.dto.responses.SubscriptionResponse;
import com.gimble.bus_stop.entities.DriverProfile;
import com.gimble.bus_stop.entities.DriverSubscription;
import com.gimble.bus_stop.entities.Payment;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.PaymentPurpose;
import com.gimble.bus_stop.enums.SubscriptionStatus;
import com.gimble.bus_stop.payments.PaymentSucceededEvent;
import com.gimble.bus_stop.repositories.DriverProfileRepository;
import com.gimble.bus_stop.repositories.DriverSubscriptionRepository;
import com.gimble.bus_stop.exceptions.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Drivers pay a small monthly fee to be listed. Besides funding the platform, a paid account leaves a
 * mobile-money / card trail, which deters people who shouldn't be near children.
 * Unpaid drivers are hidden from parents and can't take new bookings; children already booked are still served.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final DriverSubscriptionRepository subscriptionRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final DriverService driverService;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final Clock clock;

    @Value("${app.platform.driver-monthly-fee}")
    private BigDecimal monthlyFee;

    @Transactional(readOnly = true)
    public SubscriptionResponse status(User user) {
        DriverProfile me = driverService.requireProfile(user);
        DriverProfile business = me.business();
        LocalDate today = LocalDate.now(clock);
        LocalDate until = business.getSubscriptionPaidUntil();
        boolean active = business.isSubscribedOn(today);
        int drivers = driversCovered(business);
        return new SubscriptionResponse(monthlyFee.multiply(BigDecimal.valueOf(drivers)), monthlyFee, drivers,
                me.ownsBusiness(), me.isInTeam() ? me.getOperator().getName() : null, active, until,
                active ? ChronoUnit.DAYS.between(today, until) + 1 : 0,
                me.ownsBusiness()
                        ? subscriptionRepository.findByDriverIdOrderByCreatedAtDesc(business.getId()).stream()
                                .filter(s -> s.getStatus() == SubscriptionStatus.PAID)
                                .map(SubscriptionResponse.Period::from).toList()
                        : List.of());
    }

    /**
     * Pays the next month: from today, or from the day after the current paid-up period. A team pays for
     * each driver; drivers who join mid-month are covered until the next renewal.
     */
    @Transactional
    public PaymentResponse pay(User user, PayRequest request) {
        DriverProfile driver = driverService.requireApproved(user);
        if (!driver.ownsBusiness()) {
            throw new UnauthorizedException(driver.getOperator().getOwner().getUser().getFirstName()
                    + " pays the listing fee for " + driver.getOperator().getName());
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate start = driver.isSubscribedOn(today) ? driver.getSubscriptionPaidUntil().plusDays(1) : today;
        int drivers = driversCovered(driver);
        BigDecimal amount = monthlyFee.multiply(BigDecimal.valueOf(drivers));
        DriverSubscription subscription = subscriptionRepository.save(DriverSubscription.builder()
                .driver(driver)
                .periodStart(start)
                .periodEnd(start.plusMonths(1).minusDays(1))
                .amount(amount)
                .drivers(drivers)
                .build());
        return paymentService.chargeDriver(Payment.builder()
                .payer(user)
                .driver(driver)
                .subscription(subscription)
                .purpose(PaymentPurpose.SUBSCRIPTION)
                .earningAvailableOn(today)
                .build(), amount, request);
    }

    /** Drivers a business pays for: everyone in the team who drives (at least one). */
    public int driversCovered(DriverProfile business) {
        if (!business.isInTeam()) {
            return 1;
        }
        return Math.max(1, (int) driverProfileRepository.findTeam(business.getOperator().getId()).stream()
                .filter(DriverProfile::isDrives).count());
    }

    public BigDecimal feeFor(DriverProfile business) {
        return monthlyFee.multiply(BigDecimal.valueOf(driversCovered(business)));
    }

    @EventListener
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        DriverSubscription subscription = event.payment().getSubscription();
        if (subscription == null || subscription.getStatus() == SubscriptionStatus.PAID) {
            return;
        }
        subscription.setStatus(SubscriptionStatus.PAID);
        subscription.setPaidAt(Instant.now(clock));
        DriverProfile driver = subscription.getDriver();
        LocalDate current = driver.getSubscriptionPaidUntil();
        if (current == null || current.isBefore(subscription.getPeriodEnd())) {
            driver.setSubscriptionPaidUntil(subscription.getPeriodEnd());
        }
        auditService.recordSystem("DRIVER_SUBSCRIPTION_PAID", "DriverProfile", driver.getId(),
                subscription.getPeriodStart() + ".." + subscription.getPeriodEnd());
        notificationService.notify(driver.getUser(), "SUBSCRIPTION", "You're live",
                "Thanks! Parents can find and book you until " + driver.getSubscriptionPaidUntil() + ".", driver.getId());
    }

    /** Daily: a reminder 3 days before the fee runs out, and a notice the day after it lapses. */
    @Transactional
    public void remind() {
        LocalDate today = LocalDate.now(clock);
        for (DriverProfile driver : driverProfileRepository.findBySubscriptionPaidUntil(today.plusDays(3))) {
            notificationService.notify(driver.getUser(), "SUBSCRIPTION", "Listing renews soon",
                    "Your SchoolDrop listing is paid until " + driver.getSubscriptionPaidUntil()
                            + ". Pay P" + feeFor(driver) + " to stay visible to parents.", driver.getId());
        }
        for (DriverProfile driver : driverProfileRepository.findBySubscriptionPaidUntil(today.minusDays(1))) {
            notificationService.notify(driver.getUser(), "SUBSCRIPTION", "Listing paused",
                    "Your monthly fee is due. Parents can't find or book you until it's paid. "
                            + "Children already booked still ride with you.", driver.getId());
        }
    }

    public BigDecimal monthlyFee() {
        return monthlyFee;
    }
}
