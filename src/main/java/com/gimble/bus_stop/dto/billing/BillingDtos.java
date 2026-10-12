package com.gimble.bus_stop.dto.billing;

import com.gimble.bus_stop.billing.BillingSettings;
import com.gimble.bus_stop.billing.InvoicePdf;
import com.gimble.bus_stop.entities.Invoice;
import com.gimble.bus_stop.enums.InvoiceType;
import com.gimble.bus_stop.enums.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Billing: summaries, transaction history and documents (/v1/billing, /v1/admin/finance). */
public final class BillingDtos {

    private BillingDtos() {
    }

    // ─── Documents ───────────────────────────────────────────────────────────

    public record InvoiceSummary(UUID id, String number, InvoiceType type, String title, String description,
                                 BigDecimal total, String currency, boolean paidDirectly, String recipientName,
                                 Instant issuedAt) {
        public static InvoiceSummary of(Invoice i, BillingSettings s) {
            return new InvoiceSummary(i.getId(), i.getNumber(), i.getType(), InvoicePdf.title(i, s.vatRegistered()),
                    i.getDescription(), i.getTotal(), i.getCurrency(), i.isPaidDirectly(), i.getRecipientName(), i.getIssuedAt());
        }
    }

    public record InvoiceLineResponse(String description, BigDecimal quantity, BigDecimal unitAmount, BigDecimal amount) {
    }

    public record Company(String name, String address, String registration, String vatNumber, String supportEmail) {
        public static Company of(BillingSettings s) {
            return new Company(s.getCompanyName(), s.getCompanyAddress(), blankToNull(s.getCompanyRegistration()),
                    s.vatRegistered() ? s.getVatNumber() : null, s.getSupportEmail());
        }
    }

    public record InvoiceDetail(UUID id, String number, InvoiceType type, String title, String description,
                                String recipientName, String recipientEmail, String recipientPhone, String supplierName,
                                BigDecimal subtotal, BigDecimal tax, BigDecimal vatPercent, BigDecimal total, String currency,
                                PaymentMethod paymentMethod, boolean paidDirectly, Instant paidAt, String notes,
                                Instant issuedAt, String creditedInvoiceNumber, List<InvoiceLineResponse> lines,
                                Company issuer) {
        public static InvoiceDetail of(Invoice i, BillingSettings s) {
            return new InvoiceDetail(i.getId(), i.getNumber(), i.getType(), InvoicePdf.title(i, s.vatRegistered()),
                    i.getDescription(), i.getRecipientName(), i.getRecipientEmail(), i.getRecipientPhone(),
                    i.getSupplierName(), i.getSubtotal(), i.getTax(), s.vatRegistered() ? s.getVatPercent() : null,
                    i.getTotal(), i.getCurrency(), i.getPaymentMethod(), i.isPaidDirectly(), i.getPaidAt(), i.getNotes(),
                    i.getIssuedAt(), i.getCreditedInvoice() != null ? i.getCreditedInvoice().getNumber() : null,
                    i.getLines().stream().map(l -> new InvoiceLineResponse(l.getDescription(), l.getQuantity(),
                            l.getUnitAmount(), l.getAmount())).toList(),
                    Company.of(s));
        }
    }

    /** A short-lived link that downloads the PDF without the app's bearer token (browsers, share sheets). */
    public record DownloadLink(String path, Instant expiresAt) {
    }

    // ─── History ─────────────────────────────────────────────────────────────

    public enum TransactionKind { PAYMENT, REFUND, EARNING, EARNING_REFUNDED, FEE, PAYOUT }

    /**
     * One line of money history, from the viewer's side: negative = money out (a family paying, a driver
     * paying SchoolDrop, an earning taken back by a refund), positive = money in. Payouts are transfers of
     * money already earned, so they're informational (amount positive, not added to totals).
     */
    public record Transaction(UUID id, Instant date, TransactionKind kind, String title, String subtitle,
                              BigDecimal amount, String status, PaymentMethod method, boolean paidDirectly,
                              // Earnings only: what the family paid and SchoolDrop's commission
                              BigDecimal gross, BigDecimal commission,
                              UUID invoiceId, String invoiceNumber) {
    }

    // ─── Summaries ───────────────────────────────────────────────────────────

    public record Amount(String label, BigDecimal total) {
    }

    public record FamilyMonth(String month, BigDecimal paidInApp, BigDecimal paidDirect, BigDecimal refunded) {
    }

    public record FamilySummary(BigDecimal paidThisMonth, BigDecimal paidThisYear, BigDecimal paidAllTime,
                                BigDecimal paidDirectAllTime, BigDecimal refundedAllTime,
                                int awaitingPaymentCount, BigDecimal awaitingPaymentAmount,
                                List<Amount> byChild, List<FamilyMonth> months) {
    }

    public record DriverMonth(String month, BigDecimal grossInApp, BigDecimal commission, BigDecimal net,
                              BigDecimal paidDirect, BigDecimal paidToSchoolDrop) {
    }

    public record DriverSummary(BigDecimal commissionPercent,
                                BigDecimal earnedThisMonth, BigDecimal earnedThisYear,
                                BigDecimal grossAllTime, BigDecimal commissionAllTime, BigDecimal netAllTime,
                                BigDecimal paidDirectAllTime, BigDecimal refundedAllTime,
                                BigDecimal availableBalance, BigDecimal upcomingBalance, BigDecimal paidOutAllTime,
                                BigDecimal paidToSchoolDropAllTime, BigDecimal listingFeesAllTime, BigDecimal advertsAllTime,
                                LocalDate listedUntil, List<DriverMonth> months) {
    }

    public record BillingSummary(String kind, String currency, FamilySummary family, DriverSummary driver) {
    }

    // ─── Platform finance (admin) ────────────────────────────────────────────

    public record FinanceMonth(String month, BigDecimal collectedInApp, BigDecimal commission, BigDecimal listingFees,
                               BigDecimal adverts, BigDecimal refunded, BigDecimal refundedCommission,
                               BigDecimal revenue, BigDecimal paidDirect, BigDecimal payoutsSent) {
    }

    public record FinanceReport(String currency, BigDecimal commissionPercent, FinanceMonth totals, List<FinanceMonth> months) {
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
