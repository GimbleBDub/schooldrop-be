package com.gimble.bus_stop.services;

import com.gimble.bus_stop.billing.BillingSettings;
import com.gimble.bus_stop.billing.InvoicePdf;
import com.gimble.bus_stop.entities.*;
import com.gimble.bus_stop.enums.InvoiceType;
import com.gimble.bus_stop.enums.PaymentPurpose;
import com.gimble.bus_stop.enums.PaymentStatus;
import com.gimble.bus_stop.payments.PaymentSucceededEvent;
import com.gimble.bus_stop.repositories.InvoiceRepository;
import com.gimble.bus_stop.repositories.PaymentRepository;
import com.gimble.bus_stop.repositories.PayoutRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Issues billing documents as money moves:
 * <ul>
 *   <li>a family pays for transport → RECEIPT (collected by SchoolDrop for the driver, or paid to the driver directly)</li>
 *   <li>a driver pays SchoolDrop (listing fee, advert) → INVOICE</li>
 *   <li>a refund → CREDIT_NOTE against the original</li>
 *   <li>a payout is sent → STATEMENT (what parents paid, SchoolDrop's commission, what the driver received)</li>
 * </ul>
 * Every method is idempotent, so retries and the startup backfill never issue a document twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final EnumSet<InvoiceType> PAYMENT_DOCS = EnumSet.of(InvoiceType.RECEIPT, InvoiceType.INVOICE);

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PayoutRepository payoutRepository;
    private final BillingSettings settings;
    private final Clock clock;

    @EventListener
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        issueFor(event.payment());
    }

    // ─── Receipts & invoices ─────────────────────────────────────────────────

    @Transactional
    public Invoice issueFor(Payment payment) {
        Optional<Invoice> existing = invoiceRepository.findFirstByPaymentIdAndTypeIn(payment.getId(), PAYMENT_DOCS);
        if (existing.isPresent()) {
            return existing.get();
        }
        boolean ownProduct = payment.getPurpose() == PaymentPurpose.SUBSCRIPTION || payment.getPurpose() == PaymentPurpose.ADVERT;
        InvoiceType type = ownProduct ? InvoiceType.INVOICE : InvoiceType.RECEIPT;
        User payer = payment.getPayer();
        String driverName = payment.getDriver().getUser().getFullName();

        Invoice invoice = Invoice.builder()
                .type(type)
                .number(nextNumber(type))
                .recipient(payer)
                .recipientName(payer.getFullName())
                .recipientEmail(payer.getEmail())
                .recipientPhone(payer.getPhone())
                .supplierName(ownProduct ? settings.getCompanyName() : driverName)
                .payment(payment)
                .paymentMethod(payment.getMethod())
                .paidDirectly(payment.isOffline())
                .paidAt(payment.getPaidAt())
                .subtotal(payment.getAmount())
                .total(payment.getAmount())
                .tax(ownProduct ? settings.vatIncludedIn(payment.getAmount()) : BigDecimal.ZERO)
                .issuedAt(Instant.now(clock))
                .build();

        switch (payment.getPurpose()) {
            case BOOKING -> {
                Booking b = payment.getBooking();
                invoice.setHouseholdOwner(b.getParent());
                invoice.setDescription("School transport for " + b.getChild().getFirstName());
                invoice.addLine(b.getChild().getFullName() + " to " + b.getOffer().getSchool().getName() + " · "
                                + planLabel(b) + " · " + DAY.format(b.getStartDate()) + " to " + DAY.format(b.getEndDate()),
                        BigDecimal.ONE, payment.getAmount(), payment.getAmount());
            }
            case ADHOC -> {
                AdHocRequest a = payment.getAdHocRequest();
                invoice.setHouseholdOwner(a.getParent());
                invoice.setDescription("Once-off pickup for " + a.getChild().getFirstName());
                invoice.addLine("Pickup of " + a.getChild().getFullName() + " from " + a.getSchool().getName() + " on "
                                + DAY.format(LocalDate.ofInstant(a.getPickupAt(), clock.getZone())),
                        BigDecimal.ONE, payment.getAmount(), payment.getAmount());
            }
            case SUBSCRIPTION -> {
                DriverSubscription sub = payment.getSubscription();
                invoice.setDescription("Driver listing fee");
                BigDecimal drivers = BigDecimal.valueOf(sub.getDrivers());
                invoice.addLine("SchoolDrop driver listing · " + (sub.getDrivers() > 1 ? sub.getDrivers() + " drivers · " : "")
                                + DAY.format(sub.getPeriodStart()) + " to " + DAY.format(sub.getPeriodEnd()),
                        drivers, payment.getAmount().divide(drivers, 2, java.math.RoundingMode.HALF_UP), payment.getAmount());
            }
            case ADVERT -> {
                Advert advert = payment.getAdvert();
                invoice.setDescription("Advert: " + advert.getTitle());
                invoice.addLine("Sponsored listing in the SchoolDrop app: \"" + advert.getTitle() + "\"",
                        BigDecimal.ONE, payment.getAmount(), payment.getAmount());
            }
        }

        if (payment.isOffline()) {
            invoice.setNotes("Paid directly to " + driverName + " and confirmed by them on SchoolDrop. "
                    + "SchoolDrop recorded this payment but did not handle the money.");
        } else if (!ownProduct) {
            invoice.setNotes("Collected by " + settings.getCompanyName() + " on behalf of " + driverName + ".");
        }
        invoice = invoiceRepository.save(invoice);
        log.info("Issued {} {} for payment {}", type, invoice.getNumber(), payment.getId());

        // Refunded before the receipt was issued (e.g. paid after the booking expired): credit it straight away
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            creditNoteFor(payment, "Refunded");
        }
        return invoice;
    }

    // ─── Credit notes ────────────────────────────────────────────────────────

    @Transactional
    public Invoice creditNoteFor(Payment payment, String reason) {
        Optional<Invoice> existing = invoiceRepository.findFirstByPaymentIdAndTypeIn(payment.getId(), EnumSet.of(InvoiceType.CREDIT_NOTE));
        if (existing.isPresent()) {
            return existing.get();
        }
        Invoice original = invoiceRepository.findFirstByPaymentIdAndTypeIn(payment.getId(), PAYMENT_DOCS)
                .orElseGet(() -> issueFor(payment));
        // issueFor credits refunded payments itself
        existing = invoiceRepository.findFirstByPaymentIdAndTypeIn(payment.getId(), EnumSet.of(InvoiceType.CREDIT_NOTE));
        if (existing.isPresent()) {
            return existing.get();
        }
        Invoice credit = Invoice.builder()
                .type(InvoiceType.CREDIT_NOTE)
                .number(nextNumber(InvoiceType.CREDIT_NOTE))
                .recipient(original.getRecipient())
                .recipientName(original.getRecipientName())
                .recipientEmail(original.getRecipientEmail())
                .recipientPhone(original.getRecipientPhone())
                .householdOwner(original.getHouseholdOwner())
                .supplierName(original.getSupplierName())
                .payment(payment)
                .creditedInvoice(original)
                .paymentMethod(payment.getMethod())
                .paidAt(payment.getRefundedAt())
                .description("Refund of " + original.getNumber())
                .subtotal(original.getSubtotal())
                .tax(original.getTax())
                .total(original.getTotal())
                .notes(reason == null || reason.isBlank() ? null : "Reason: " + truncate(reason, 400))
                .issuedAt(Instant.now(clock))
                .build();
        credit.addLine("Refund: " + original.getDescription(), BigDecimal.ONE, original.getTotal(), original.getTotal());
        credit = invoiceRepository.save(credit);
        log.info("Issued credit note {} for payment {}", credit.getNumber(), payment.getId());
        return credit;
    }

    // ─── Payout statements ───────────────────────────────────────────────────

    @Transactional
    public Invoice statementFor(Payout payout) {
        Optional<Invoice> existing = invoiceRepository.findFirstByPayoutIdAndType(payout.getId(), InvoiceType.STATEMENT);
        if (existing.isPresent()) {
            return existing.get();
        }
        User driver = payout.getDriver().getUser();
        List<Payment> payments = paymentRepository.findByPayoutIdOrderByPaidAt(payout.getId());
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;

        Invoice statement = Invoice.builder()
                .type(InvoiceType.STATEMENT)
                .number(nextNumber(InvoiceType.STATEMENT))
                .recipient(driver)
                .recipientName(driver.getFullName())
                .recipientEmail(driver.getEmail())
                .recipientPhone(driver.getPhone())
                .supplierName(settings.getCompanyName())
                .payout(payout)
                .paymentMethod(payout.getMethod())
                .paidAt(payout.getProcessedAt())
                .description("Payout of P" + payout.getAmount().toPlainString())
                .issuedAt(Instant.now(clock))
                .build();
        for (Payment p : payments) {
            String what = p.getBooking() != null
                    ? p.getBooking().getChild().getFirstName() + " · " + planLabel(p.getBooking())
                    : p.getAdHocRequest().getChild().getFirstName() + " · once-off pickup";
            String when = p.getPaidAt() != null ? DAY.format(LocalDate.ofInstant(p.getPaidAt(), clock.getZone())) + " · " : "";
            String driverNote = p.getDriver().getId().equals(payout.getDriver().getId()) ? "" : " · driven by " + p.getDriver().getUser().getFullName();
            statement.addLine("Paid by " + p.getPayer().getFullName() + " · " + when + what + driverNote, BigDecimal.ONE, p.getAmount(), p.getAmount());
            gross = gross.add(p.getAmount());
            commission = commission.add(p.getPlatformFee());
        }
        if (commission.signum() > 0) {
            statement.addLine("SchoolDrop commission", BigDecimal.ONE, commission.negate(), commission.negate());
        }
        statement.setSubtotal(gross);
        statement.setTax(settings.vatIncludedIn(commission));
        statement.setTotal(payout.getAmount());
        statement.setNotes("Sent to your " + InvoicePdf.methodLabel(payout.getMethod().name())
                + " account" + (payout.getReference() != null ? ", reference " + payout.getReference() : "") + "."
                + (settings.vatRegistered() ? " The commission includes VAT shown above." : ""));
        statement = invoiceRepository.save(statement);
        log.info("Issued statement {} for payout {}", statement.getNumber(), payout.getId());
        return statement;
    }

    // ─── Backfill ────────────────────────────────────────────────────────────

    /** Issues documents for money that moved before invoicing existed (or while a listener failed). */
    @Transactional
    public int backfill() {
        int issued = 0;
        for (Payment p : paymentRepository.findWithoutDocument()) {
            issueFor(p);
            issued++;
        }
        for (Payment p : paymentRepository.findRefundedWithoutCreditNote()) {
            creditNoteFor(p, "Refunded");
            issued++;
        }
        for (Payout payout : payoutRepository.findPaidWithoutStatement()) {
            statementFor(payout);
            issued++;
        }
        return issued;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String nextNumber(InvoiceType type) {
        long n = switch (type) {
            case RECEIPT -> invoiceRepository.nextReceiptNumber();
            case INVOICE -> invoiceRepository.nextInvoiceNumber();
            case CREDIT_NOTE -> invoiceRepository.nextCreditNoteNumber();
            case STATEMENT -> invoiceRepository.nextStatementNumber();
        };
        String prefix = switch (type) {
            case RECEIPT -> "RCT";
            case INVOICE -> "INV";
            case CREDIT_NOTE -> "CRN";
            case STATEMENT -> "STM";
        };
        return "%s-%d-%06d".formatted(prefix, Year.now(clock).getValue(), n);
    }

    private static String planLabel(Booking b) {
        if (b.getTerm() != null) {
            return b.getTerm().getName();
        }
        return switch (b.getPlan()) {
            case MONTHLY -> "monthly";
            case TERMLY -> "termly";
            case PER_TRIP -> b.getTripCount() != null ? b.getTripCount() + " trips" : "per trip";
        };
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
