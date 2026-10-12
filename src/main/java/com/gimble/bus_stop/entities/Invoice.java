package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.InvoiceType;
import com.gimble.bus_stop.enums.PaymentMethod;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A billing document. Names, contact details and amounts are snapshots taken when it was issued,
 * so later profile changes don't rewrite history. Numbers are sequential per type and never reused.
 */
@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 30)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceType type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Column(nullable = false, length = 200)
    private String recipientName;

    private String recipientEmail;

    @Column(length = 20)
    private String recipientPhone;

    // The family this belongs to: the owner and guardians can all see it
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "household_owner_id")
    private User householdOwner;

    // Who provided what was paid for: the driver (transport) or SchoolDrop (fees, adverts, commission)
    @Column(nullable = false, length = 200)
    private String supplierName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payout_id")
    private Payout payout;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credited_invoice_id")
    private Invoice creditedInvoice;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Builder.Default
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal tax = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Builder.Default
    @Column(nullable = false, length = 3)
    private String currency = "BWP";

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentMethod paymentMethod;

    // Paid to the driver directly (cash / EFT): SchoolDrop recorded it but didn't handle the money
    @Builder.Default
    @Column(nullable = false)
    private boolean paidDirectly = false;

    private Instant paidAt;

    @Column(length = 500)
    private String notes;

    @Column(nullable = false)
    private Instant issuedAt;

    @Builder.Default
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<InvoiceLine> lines = new ArrayList<>();

    public void addLine(String description, BigDecimal quantity, BigDecimal unitAmount, BigDecimal amount) {
        lines.add(InvoiceLine.builder().invoice(this).position(lines.size() + 1).description(description)
                .quantity(quantity).unitAmount(unitAmount).amount(amount).build());
    }
}
