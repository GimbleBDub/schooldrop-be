package com.gimble.bus_stop.services;

import com.gimble.bus_stop.billing.BillingSettings;
import com.gimble.bus_stop.billing.InvoiceLinks;
import com.gimble.bus_stop.billing.InvoicePdf;
import com.gimble.bus_stop.dto.billing.BillingDtos.*;
import com.gimble.bus_stop.dto.responses.PageResponse;
import com.gimble.bus_stop.entities.*;
import com.gimble.bus_stop.enums.*;
import com.gimble.bus_stop.exceptions.BusinessException;
import com.gimble.bus_stop.exceptions.ResourceNotFoundException;
import com.gimble.bus_stop.repositories.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Billing for families (what they paid, refunds, receipts) and drivers (earnings, SchoolDrop's commission,
 * payouts, what they paid SchoolDrop, statements and invoices), plus the platform finance report for staff.
 * Helpers in a family, school staff and kids have no billing.
 */
@Service
@RequiredArgsConstructor
public class BillingService {

    private static final int MONTHS = 12;
    private static final int HISTORY_LIMIT = 300;

    private final PaymentRepository paymentRepository;
    private final PayoutRepository payoutRepository;
    private final InvoiceRepository invoiceRepository;
    private final BookingRepository bookingRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final HouseholdService householdService;
    private final BillingSettings settings;
    private final InvoicePdf invoicePdf;
    private final InvoiceLinks invoiceLinks;
    private final Clock clock;

    // ─── Who sees what ───────────────────────────────────────────────────────

    /** The family owner whose billing this user may see, or null for drivers. Throws for everyone else. */
    private User familyOwnerFor(User user) {
        return householdService.requireFamilyManager(user);
    }

    /** A driver on their own, or a team's owner: the business whose money this is. */
    private DriverProfile driverFor(User user) {
        DriverProfile driver = driverProfileRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found"));
        if (!driver.ownsBusiness()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "Earnings for " + driver.getOperator().getName() + " go to "
                    + driver.getOperator().getOwner().getUser().getFullName());
        }
        return driver;
    }

    private void requireBillingAccount(User user) {
        if (user.getRole() != Role.PARENT && user.getRole() != Role.DRIVER) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "There's no billing for this account");
        }
    }

    // ─── Summary ─────────────────────────────────────────────────────────────

    @Transactional
    public BillingSummary summary(User user) {
        requireBillingAccount(user);
        if (user.getRole() == Role.PARENT) {
            return new BillingSummary("FAMILY", "BWP", familySummary(familyOwnerFor(user)), null);
        }
        return new BillingSummary("DRIVER", "BWP", null, driverSummary(driverFor(user)));
    }

    private FamilySummary familySummary(User owner) {
        List<Payment> payments = paymentRepository.findSettledForFamily(owner.getId());
        LocalDate today = LocalDate.now(clock);
        YearMonth thisMonth = YearMonth.from(today);
        Map<YearMonth, BigDecimal[]> months = emptyMonths(thisMonth, 3);

        BigDecimal paidThisMonth = BigDecimal.ZERO, paidThisYear = BigDecimal.ZERO, paidAll = BigDecimal.ZERO;
        BigDecimal direct = BigDecimal.ZERO, refunded = BigDecimal.ZERO;
        Map<String, BigDecimal> byChild = new LinkedHashMap<>();
        for (Payment p : payments) {
            YearMonth paidMonth = month(p.getPaidAt());
            paidAll = paidAll.add(p.getAmount());
            if (paidMonth != null && paidMonth.equals(thisMonth)) {
                paidThisMonth = paidThisMonth.add(p.getAmount());
            }
            if (paidMonth != null && paidMonth.getYear() == today.getYear()) {
                paidThisYear = paidThisYear.add(p.getAmount());
            }
            if (p.isOffline()) {
                direct = direct.add(p.getAmount());
            }
            add(months, paidMonth, p.isOffline() ? 1 : 0, p.getAmount());
            if (p.getStatus() == PaymentStatus.REFUNDED) {
                refunded = refunded.add(p.getAmount());
                add(months, month(p.getRefundedAt()), 2, p.getAmount());
            }
            Child child = p.getBooking() != null ? p.getBooking().getChild() : p.getAdHocRequest().getChild();
            BigDecimal net = p.getStatus() == PaymentStatus.REFUNDED ? BigDecimal.ZERO : p.getAmount();
            byChild.merge(child.getFirstName(), net, BigDecimal::add);
        }

        List<Booking> awaiting = bookingRepository.findByParentId(owner.getId()).stream()
                .filter(b -> b.getStatus() == BookingStatus.PENDING_PAYMENT).toList();
        return new FamilySummary(paidThisMonth, paidThisYear, paidAll, direct, refunded, awaiting.size(),
                awaiting.stream().map(Booking::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                byChild.entrySet().stream().map(e -> new Amount(e.getKey(), e.getValue()))
                        .sorted(Comparator.comparing(Amount::total).reversed()).toList(),
                months.entrySet().stream()
                        .map(e -> new FamilyMonth(e.getKey().toString(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                        .toList());
    }

    private DriverSummary driverSummary(DriverProfile driver) {
        LocalDate today = LocalDate.now(clock);
        YearMonth thisMonth = YearMonth.from(today);
        // gross in app, commission, net (in app + direct − refunds), direct, paid to SchoolDrop
        Map<YearMonth, BigDecimal[]> months = emptyMonths(thisMonth, 5);

        BigDecimal gross = BigDecimal.ZERO, commission = BigDecimal.ZERO, net = BigDecimal.ZERO, direct = BigDecimal.ZERO;
        BigDecimal refunded = BigDecimal.ZERO, available = BigDecimal.ZERO, upcoming = BigDecimal.ZERO;
        BigDecimal earnedThisMonth = BigDecimal.ZERO, earnedThisYear = BigDecimal.ZERO;
        for (Payment p : paymentRepository.findIncomeForDriver(driver.getId())) {
            YearMonth m = month(p.getPaidAt());
            boolean kept = p.getStatus() == PaymentStatus.SUCCEEDED;
            if (p.isOffline()) {
                direct = direct.add(p.getAmount());
                add(months, m, 3, p.getAmount());
            } else {
                gross = gross.add(p.getAmount());
                commission = commission.add(p.getPlatformFee());
                add(months, m, 0, p.getAmount());
                add(months, m, 1, p.getPlatformFee());
            }
            if (kept) {
                net = net.add(p.getDriverAmount());
                add(months, m, 2, p.getDriverAmount());
                if (m != null && m.equals(thisMonth)) {
                    earnedThisMonth = earnedThisMonth.add(p.getDriverAmount());
                }
                if (m != null && m.getYear() == today.getYear()) {
                    earnedThisYear = earnedThisYear.add(p.getDriverAmount());
                }
                if (!p.isOffline() && p.getPayout() == null) {
                    if (p.getEarningAvailableOn().isAfter(today)) {
                        upcoming = upcoming.add(p.getDriverAmount());
                    } else {
                        available = available.add(p.getDriverAmount());
                    }
                }
            } else {
                refunded = refunded.add(p.getDriverAmount());
            }
        }

        BigDecimal listing = BigDecimal.ZERO, adverts = BigDecimal.ZERO;
        for (Payment p : paymentRepository.findChargesForDriver(driver.getId())) {
            if (p.getStatus() != PaymentStatus.SUCCEEDED) {
                continue;
            }
            if (p.getPurpose() == PaymentPurpose.SUBSCRIPTION) {
                listing = listing.add(p.getAmount());
            } else {
                adverts = adverts.add(p.getAmount());
            }
            add(months, month(p.getPaidAt()), 4, p.getAmount());
        }

        BigDecimal paidOut = payoutRepository.findByDriverIdOrderByCreatedAtDesc(driver.getId()).stream()
                .filter(p -> p.getStatus() == PayoutStatus.PAID)
                .map(Payout::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new DriverSummary(settings.getPlatformFeePercent(), earnedThisMonth, earnedThisYear,
                gross, commission, net, direct, refunded, available, upcoming, paidOut,
                listing.add(adverts), listing, adverts, driver.getSubscriptionPaidUntil(),
                months.entrySet().stream().map(e -> new DriverMonth(e.getKey().toString(), e.getValue()[0],
                        e.getValue()[1], e.getValue()[2], e.getValue()[3], e.getValue()[4])).toList());
    }

    // ─── History ─────────────────────────────────────────────────────────────

    @Transactional
    public List<Transaction> transactions(User user) {
        requireBillingAccount(user);
        List<Transaction> lines = new ArrayList<>();
        if (user.getRole() == Role.PARENT) {
            User owner = familyOwnerFor(user);
            List<Payment> payments = paymentRepository.findSettledForFamily(owner.getId());
            Map<UUID, Invoice> receipts = documentsFor(payments, InvoiceType.RECEIPT);
            Map<UUID, Invoice> credits = documentsFor(payments, InvoiceType.CREDIT_NOTE);
            for (Payment p : payments) {
                String what = familyTitle(p);
                String who = p.getPayer().getId().equals(user.getId()) ? "You" : p.getPayer().getFirstName();
                String how = p.isOffline() ? "paid " + p.getDriver().getUser().getFirstName() + " directly"
                        : "paid with " + method(p.getMethod());
                lines.add(tx(p.getId(), p.getPaidAt(), TransactionKind.PAYMENT, what, who + " " + how,
                        p.getAmount().negate(), p, null, null, receipts.get(p.getId())));
                if (p.getStatus() == PaymentStatus.REFUNDED) {
                    lines.add(tx(p.getId(), p.getRefundedAt(), TransactionKind.REFUND, "Refund · " + what,
                            "Back to " + method(p.getMethod()), p.getAmount(), p, null, null, credits.get(p.getId())));
                }
            }
        } else {
            DriverProfile driver = driverFor(user);
            List<Payment> income = paymentRepository.findIncomeForDriver(driver.getId());
            List<Payment> charges = paymentRepository.findChargesForDriver(driver.getId());
            List<Payment> all = new ArrayList<>(income);
            all.addAll(charges);
            Map<UUID, Invoice> docs = documentsFor(all, InvoiceType.INVOICE);
            Map<UUID, Invoice> credits = documentsFor(all, InvoiceType.CREDIT_NOTE);
            for (Payment p : income) {
                // In a team, say which driver earned it
                String who = p.getPayer().getFullName()
                        + (p.getDriver().getId().equals(driver.getId()) ? "" : " → " + p.getDriver().getUser().getFirstName());
                String subtitle = p.isOffline() ? who + " paid you directly"
                        : who + " paid P" + p.getAmount().toPlainString() + " · commission P" + p.getPlatformFee().toPlainString();
                lines.add(tx(p.getId(), p.getPaidAt(), TransactionKind.EARNING, driverTitle(p), subtitle,
                        p.getDriverAmount(), p, p.getAmount(), p.getPlatformFee(), null));
                if (p.getStatus() == PaymentStatus.REFUNDED) {
                    lines.add(tx(p.getId(), p.getRefundedAt(), TransactionKind.EARNING_REFUNDED, "Refunded · " + driverTitle(p),
                            "Refunded to " + who, p.getDriverAmount().negate(), p, null, null, null));
                }
            }
            for (Payment p : charges) {
                String what = p.getPurpose() == PaymentPurpose.SUBSCRIPTION ? "Listing fee" : "Advert";
                lines.add(tx(p.getId(), p.getPaidAt(), TransactionKind.FEE, what, "Paid to SchoolDrop with " + method(p.getMethod()),
                        p.getAmount().negate(), p, null, null, docs.get(p.getId())));
                if (p.getStatus() == PaymentStatus.REFUNDED) {
                    lines.add(tx(p.getId(), p.getRefundedAt(), TransactionKind.REFUND, "Refund · " + what,
                            "Back to " + method(p.getMethod()), p.getAmount(), p, null, null, credits.get(p.getId())));
                }
            }
            for (Payout payout : payoutRepository.findByDriverIdOrderByCreatedAtDesc(driver.getId())) {
                Invoice statement = payout.getStatus() == PayoutStatus.PAID
                        ? invoiceRepository.findFirstByPayoutIdAndType(payout.getId(), InvoiceType.STATEMENT).orElse(null) : null;
                String status = switch (payout.getStatus()) {
                    case PAID -> "Sent to your " + method(payout.getMethod()) + " account";
                    case PENDING -> "Being sent to your " + method(payout.getMethod()) + " account";
                    default -> "Failed: " + Objects.requireNonNullElse(payout.getFailureReason(), "check your payout details");
                };
                lines.add(new Transaction(payout.getId(),
                        payout.getProcessedAt() != null ? payout.getProcessedAt() : payout.getCreatedAt(),
                        TransactionKind.PAYOUT, "Payout", status, payout.getAmount(), payout.getStatus().name(),
                        payout.getMethod(), false, null, null,
                        statement != null ? statement.getId() : null, statement != null ? statement.getNumber() : null));
            }
        }
        lines.sort(Comparator.comparing(Transaction::date, Comparator.nullsLast(Comparator.reverseOrder())));
        return lines.size() > HISTORY_LIMIT ? lines.subList(0, HISTORY_LIMIT) : lines;
    }

    // ─── Documents ───────────────────────────────────────────────────────────

    @Transactional
    public List<InvoiceSummary> invoices(User user) {
        requireBillingAccount(user);
        UUID ownerId = user.getRole() == Role.PARENT ? familyOwnerFor(user).getId() : null;
        return invoiceRepository.findVisibleTo(user.getId(), ownerId).stream()
                .map(i -> InvoiceSummary.of(i, settings)).toList();
    }

    @Transactional
    public InvoiceDetail invoice(User user, UUID invoiceId) {
        return InvoiceDetail.of(requireVisible(user, invoiceId), settings);
    }

    @Transactional
    public DownloadLink downloadLink(User user, UUID invoiceId) {
        requireVisible(user, invoiceId);
        return invoiceLinks.create(invoiceId);
    }

    /** Opened from a signed link: the signature is the permission. */
    @Transactional(readOnly = true)
    public Pdf pdfFromLink(UUID invoiceId, long expires, String signature) {
        invoiceLinks.verify(invoiceId, expires, signature);
        return pdf(requireInvoice(invoiceId));
    }

    @Transactional
    public Pdf pdf(User user, UUID invoiceId) {
        return pdf(requireVisible(user, invoiceId));
    }

    private Pdf pdf(Invoice invoice) {
        return new Pdf(invoice.getNumber() + ".pdf", invoicePdf.render(invoice));
    }

    public record Pdf(String filename, byte[] bytes) {
    }

    private Invoice requireVisible(User user, UUID invoiceId) {
        Invoice invoice = requireInvoice(invoiceId);
        if (user.getRole() == Role.ADMIN && user.getStaffPermissions().stream().anyMatch(p ->
                p == StaffPermission.SUPER_ADMIN || p == StaffPermission.FINANCE || p == StaffPermission.SUPPORT
                        || p == StaffPermission.VIEWER)) {
            return invoice;
        }
        if (invoice.getRecipient().getId().equals(user.getId())) {
            return invoice;
        }
        if (user.getRole() == Role.PARENT && invoice.getHouseholdOwner() != null) {
            HouseholdService.Context family = householdService.resolve(user);
            if (family.canManageFamily() && family.owner().getId().equals(invoice.getHouseholdOwner().getId())) {
                return invoice;
            }
        }
        // Same answer as "doesn't exist", so ids can't be probed
        throw new ResourceNotFoundException("Document not found");
    }

    private Invoice requireInvoice(UUID invoiceId) {
        return invoiceRepository.findById(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Document not found"));
    }

    // ─── Admin ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<InvoiceSummary> adminSearch(InvoiceType type, String query, Pageable pageable) {
        String q = query == null || query.isBlank() ? null : query.trim();
        return PageResponse.from(invoiceRepository.adminSearch(type, q, pageable).map(i -> InvoiceSummary.of(i, settings)));
    }

    /** SchoolDrop's money by month: what went through the app, what SchoolDrop earned, refunds, payouts. */
    @Transactional(readOnly = true)
    public FinanceReport financeReport(int monthCount) {
        int n = Math.max(1, Math.min(monthCount, 36));
        ZoneId zone = clock.getZone();
        YearMonth last = YearMonth.now(clock);
        YearMonth first = last.minusMonths(n - 1);
        Instant from = first.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = last.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();

        // collected in app, commission, listing, adverts, refunded, refunded commission, paid direct, payouts
        Map<YearMonth, BigDecimal[]> months = emptyMonths(last, n, 8);
        for (Payment p : paymentRepository.findSettledBetween(from, to)) {
            YearMonth m = month(p.getPaidAt());
            switch (p.getPurpose()) {
                case BOOKING, ADHOC -> {
                    if (p.isOffline()) {
                        add(months, m, 6, p.getAmount());
                    } else {
                        add(months, m, 0, p.getAmount());
                        add(months, m, 1, p.getPlatformFee());
                    }
                }
                case SUBSCRIPTION -> add(months, m, 2, p.getAmount());
                case ADVERT -> add(months, m, 3, p.getAmount());
            }
            if (p.getStatus() == PaymentStatus.REFUNDED) {
                YearMonth rm = month(p.getRefundedAt());
                add(months, rm, 4, p.getAmount());
                add(months, rm, 5, p.getPlatformFee());
            }
        }
        for (Payout payout : payoutRepository.findPaidBetween(from, to)) {
            add(months, month(payout.getProcessedAt()), 7, payout.getAmount());
        }

        List<FinanceMonth> rows = months.entrySet().stream().map(e -> financeMonth(e.getKey().toString(), e.getValue())).toList();
        BigDecimal[] sum = new BigDecimal[8];
        Arrays.fill(sum, BigDecimal.ZERO);
        months.values().forEach(v -> {
            for (int i = 0; i < 8; i++) {
                sum[i] = sum[i].add(v[i]);
            }
        });
        return new FinanceReport("BWP", settings.getPlatformFeePercent(), financeMonth("total", sum), rows);
    }

    private static FinanceMonth financeMonth(String label, BigDecimal[] v) {
        // SchoolDrop keeps its commission and its own sales, less whatever of them was refunded
        BigDecimal revenue = v[1].add(v[2]).add(v[3]).subtract(v[5]);
        return new FinanceMonth(label, v[0], v[1], v[2], v[3], v[4], v[5], revenue, v[6], v[7]);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Map<UUID, Invoice> documentsFor(List<Payment> payments, InvoiceType type) {
        if (payments.isEmpty()) {
            return Map.of();
        }
        List<InvoiceType> types = type == InvoiceType.CREDIT_NOTE ? List.of(InvoiceType.CREDIT_NOTE)
                : List.of(InvoiceType.RECEIPT, InvoiceType.INVOICE);
        return invoiceRepository.findByPaymentIdInAndTypeIn(payments.stream().map(Payment::getId).toList(), types).stream()
                .collect(Collectors.toMap(i -> i.getPayment().getId(), Function.identity(), (a, b) -> a));
    }

    private static Transaction tx(UUID id, Instant date, TransactionKind kind, String title, String subtitle,
                                  BigDecimal amount, Payment p, BigDecimal gross, BigDecimal commission, Invoice doc) {
        return new Transaction(id, date != null ? date : p.getCreatedAt(), kind, title, subtitle, amount,
                p.getStatus().name(), p.getMethod(), p.isOffline(), gross, commission,
                doc != null ? doc.getId() : null, doc != null ? doc.getNumber() : null);
    }

    private static String familyTitle(Payment p) {
        if (p.getBooking() != null) {
            return "Transport · " + p.getBooking().getChild().getFirstName();
        }
        return "Once-off pickup · " + p.getAdHocRequest().getChild().getFirstName();
    }

    private static String driverTitle(Payment p) {
        if (p.getBooking() != null) {
            return p.getBooking().getChild().getFirstName() + " · " + p.getBooking().getPlan().name().replace('_', ' ').toLowerCase();
        }
        return p.getAdHocRequest().getChild().getFirstName() + " · once-off pickup";
    }

    private static String method(PaymentMethod method) {
        if (method == null) {
            return "—";
        }
        return switch (method) {
            case ORANGE_MONEY -> "Orange Money";
            case MYZAKA -> "MyZaka";
            case SMEGA -> "Smega";
            case CARD -> "card";
            case BANK_TRANSFER -> "bank";
            case CASH -> "cash";
        };
    }

    private YearMonth month(Instant instant) {
        return instant == null ? null : YearMonth.from(instant.atZone(clock.getZone()));
    }

    private static Map<YearMonth, BigDecimal[]> emptyMonths(YearMonth last, int columns) {
        return emptyMonths(last, MONTHS, columns);
    }

    /** Oldest first, so charts read left to right. */
    private static Map<YearMonth, BigDecimal[]> emptyMonths(YearMonth last, int count, int columns) {
        Map<YearMonth, BigDecimal[]> months = new LinkedHashMap<>();
        for (int i = count - 1; i >= 0; i--) {
            BigDecimal[] row = new BigDecimal[columns];
            Arrays.fill(row, BigDecimal.ZERO);
            months.put(last.minusMonths(i), row);
        }
        return months;
    }

    private static void add(Map<YearMonth, BigDecimal[]> months, YearMonth month, int column, BigDecimal amount) {
        if (month == null || amount == null) {
            return;
        }
        BigDecimal[] row = months.get(month);
        if (row != null) {
            row[column] = row[column].add(amount);
        }
    }
}
