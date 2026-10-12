package com.gimble.bus_stop.dto.responses;

import com.gimble.bus_stop.entities.DriverProfile;
import com.gimble.bus_stop.entities.TransportOffer;
import com.gimble.bus_stop.entities.Vehicle;
import com.gimble.bus_stop.enums.TripCoverage;
import com.gimble.bus_stop.enums.VehicleType;

import java.math.BigDecimal;
import java.util.UUID;

public record OfferResponse(
        UUID id,
        UUID schoolId,
        String schoolName,
        DriverCard driver,
        VehicleCard vehicle,
        TripCoverage coverage,
        int capacity,
        // Free seats today; the exact period is checked when booking
        int seatsAvailable,
        String serviceArea,
        String morningPickupTime,
        BigDecimal pricePerTrip,
        BigDecimal pricePerMonth,
        BigDecimal pricePerTerm,
        BigDecimal adHocFare,
        boolean active,
        // The driver is advertising at this school
        boolean featured
) {
    /** What a parent sees about the driver: enough to recognise them, nothing sensitive. */
    public record DriverCard(UUID id, String firstName, String lastName, String profilePictureUrl, String phone,
                             // The team / transport company the driver drives for, if any
                             String companyName) {
        public static DriverCard from(DriverProfile d) {
            return new DriverCard(d.getId(), d.getUser().getFirstName(), d.getUser().getLastName(),
                    d.getUser().getProfilePictureUrl(), d.getUser().getPhone(),
                    d.getOperator() != null ? d.getOperator().getName() : null);
        }
    }

    public record VehicleCard(UUID id, String make, String model, String colour, VehicleType type,
                              String plateNumber, int seatCapacity) {
        public static VehicleCard from(Vehicle v) {
            return new VehicleCard(v.getId(), v.getMake(), v.getModel(), v.getColour(), v.getType(),
                    v.getPlateNumber(), v.getSeatCapacity());
        }
    }

    public static OfferResponse from(TransportOffer o, int seatsAvailable) {
        return from(o, seatsAvailable, false);
    }

    public static OfferResponse from(TransportOffer o, int seatsAvailable, boolean featured) {
        return new OfferResponse(o.getId(), o.getSchool().getId(), o.getSchool().getName(),
                DriverCard.from(o.getDriver()), VehicleCard.from(o.getVehicle()), o.getCoverage(), o.getCapacity(),
                seatsAvailable, o.getServiceArea(), o.getMorningPickupTime(), o.getPricePerTrip(),
                o.getPricePerMonth(), o.getPricePerTerm(), o.getAdHocFare(), o.isActive(), featured);
    }
}
