package com.gimble.bus_stop.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * Background housekeeping. Assumes a single app instance; with several, add a lock (e.g. ShedLock)
 * so each job runs once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledJobs {

    private final BookingService bookingService;
    private final AdHocService adHocService;
    private final PaymentService paymentService;
    private final PayoutService payoutService;
    private final DriverService driverService;
    private final TripService tripService;
    private final SubscriptionService subscriptionService;
    private final AdvertService advertService;

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    public void releaseExpiredHolds() {
        int bookings = bookingService.expireUnpaidHolds();
        int adHoc = adHocService.expireStale();
        int payments = paymentService.cancelStalePending();
        int reminders = tripService.remindUnconfirmedDropoffs();
        if (bookings + adHoc + payments + reminders > 0) {
            log.info("Expired {} booking holds, {} ad-hoc requests, {} stale payments", bookings, adHoc, payments);
        }
    }

    @Scheduled(cron = "0 30 0 * * *", zone = "${app.timezone}")
    public void nightly() {
        log.info("Completed {} finished bookings", bookingService.completeFinished());
        driverService.expireDocuments();
        subscriptionService.remind();
        log.info("Expired {} adverts", advertService.expire());
    }

    // Mondays 06:00 — finance sends the money during the day
    @Scheduled(cron = "0 0 6 * * MON", zone = "${app.timezone}")
    public void weeklyPayouts() {
        payoutService.createPayouts(null);
    }
}
