package com.gimble.bus_stop.billing;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** SchoolDrop's details as printed on billing documents (app.billing.*). */
@Component
@Getter
public class BillingSettings {

    @Value("${app.billing.company-name}")
    private String companyName;

    @Value("${app.billing.company-address}")
    private String companyAddress;

    @Value("${app.billing.company-registration:}")
    private String companyRegistration;

    @Value("${app.billing.support-email}")
    private String supportEmail;

    @Value("${app.billing.vat-number:}")
    private String vatNumber;

    @Value("${app.billing.vat-percent:0}")
    private BigDecimal vatPercent;

    @Value("${app.billing.link-minutes:10}")
    private long linkMinutes;

    @Value("${app.platform.fee-percent}")
    private BigDecimal platformFeePercent;

    public boolean vatRegistered() {
        return vatNumber != null && !vatNumber.isBlank() && vatPercent.signum() > 0;
    }

    /** The VAT inside a VAT-inclusive amount; zero until SchoolDrop is VAT registered. */
    public BigDecimal vatIncludedIn(BigDecimal grossAmount) {
        if (!vatRegistered()) {
            return BigDecimal.ZERO;
        }
        return grossAmount.multiply(vatPercent).divide(vatPercent.add(BigDecimal.valueOf(100)), 2, RoundingMode.HALF_UP);
    }
}
