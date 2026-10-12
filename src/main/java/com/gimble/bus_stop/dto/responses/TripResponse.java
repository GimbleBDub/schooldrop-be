package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.Trip;
import com.gimble.bus_stop.entities.TripPassenger;
import com.gimble.bus_stop.enums.PassengerStatus;
import com.gimble.bus_stop.enums.TripLeg;
import com.gimble.bus_stop.enums.TripStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

public record TripResponse(
        UUID id,
        LocalDate tripDate,
        TripLeg leg,
        TripStatus status,
        boolean adHoc,
        UUID schoolId,
        String schoolName,
        String driverName,
        String driverPhone,
        String driverPhotoUrl,
        String vehicle,
        String vehiclePlate,
        Instant startedAt,
        Instant completedAt,
        // Only while the trip is in progress
        Double lastLatitude,
        Double lastLongitude,
        Instant lastLocationAt,
        List<Passenger> passengers
) {
    public record Passenger(
            UUID id,
            UUID childId,
            String childName,
            String grade,
            String specialNeeds,
            String address,
            Double latitude,
            Double longitude,
            String parentName,
            String parentPhone,
            PassengerStatus status,
            // Driver's side
            Instant pickedUpAt,
            Instant droppedOffAt,
            String handedTo,
            // The other side: family (at home) or school staff (at school)
            Instant pickupConfirmedAt,
            String pickupConfirmedBy,
            Instant dropoffConfirmedAt,
            String dropoffConfirmedBy,
            Instant disputedAt,
            String disputeNote,
            Instant kidConfirmedPickupAt,
            Instant kidConfirmedDropoffAt
    ) {
        static Passenger from(TripPassenger p) {
            var child = p.getChild();
            var booking = p.getBooking();
            var adHoc = p.getTrip().getAdHocRequest();
            String address = booking != null ? booking.getPickupAddress() : adHoc != null ? adHoc.getDropoffAddress() : null;
            Double lat = booking != null ? booking.getPickupLatitude() : adHoc != null ? adHoc.getDropoffLatitude() : null;
            Double lng = booking != null ? booking.getPickupLongitude() : adHoc != null ? adHoc.getDropoffLongitude() : null;
            return new Passenger(p.getId(), child.getId(), child.getFullName(), child.getGrade(), child.getSpecialNeeds(),
                    address, lat, lng, child.getParent().getFullName(), child.getParent().getPhone(), p.getStatus(),
                    p.getPickedUpAt(), p.getDroppedOffAt(), p.getHandedTo(),
                    p.getPickupConfirmedAt(), name(p.getPickupConfirmedBy()),
                    p.getDropoffConfirmedAt(), name(p.getDropoffConfirmedBy()),
                    p.getDisputedAt(), p.getDisputeNote(),
                    p.getKidConfirmedPickupAt(), p.getKidConfirmedDropoffAt());
        }

        private static String name(com.gimble.bus_stop.entities.User user) {
            return user != null ? user.getFullName() : null;
        }
    }

    /** visible: which passengers this viewer may see (a parent only sees their own children). */
    public static TripResponse from(Trip t, Predicate<TripPassenger> visible) {
        boolean live = t.getStatus() == TripStatus.IN_PROGRESS;
        var driverUser = t.getDriver().getUser();
        return new TripResponse(t.getId(), t.getTripDate(), t.getLeg(), t.getStatus(), t.getAdHocRequest() != null,
                t.getSchool().getId(), t.getSchool().getName(), driverUser.getFullName(), driverUser.getPhone(),
                driverUser.getProfilePictureUrl(), t.getVehicle().getDisplayName(), t.getVehicle().getPlateNumber(),
                t.getStartedAt(), t.getCompletedAt(),
                live ? t.getLastLatitude() : null, live ? t.getLastLongitude() : null,
                live ? t.getLastLocationAt() : null,
                t.getPassengers().stream().filter(visible).map(Passenger::from).toList());
    }
}
