package com.gimble.bus_stop.dto.requests;

import com.gimble.bus_stop.enums.PaymentMethod;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Driver's own details. Omang/licence are locked once the driver is approved. */
public record DriverProfileRequest(
        @Pattern(regexp = "^[0-9]{9}$", message = "must be a 9-digit Omang number") String omangNumber,
        @Size(max = 40) String licenceNumber,
        @Size(max = 10) String licenceCode,
        LocalDate licenceExpiry,
        @Past LocalDate dateOfBirth,
        @Size(max = 255) String homeArea,
        Boolean acceptsAdHoc,
        PaymentMethod payoutMethod,
        @Size(max = 60) String payoutAccount
) {
}
