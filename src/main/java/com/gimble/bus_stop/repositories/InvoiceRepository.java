package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.Invoice;
import com.gimble.bus_stop.enums.InvoiceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findFirstByPaymentIdAndTypeIn(UUID paymentId, Collection<InvoiceType> types);

    Optional<Invoice> findFirstByPayoutIdAndType(UUID payoutId, InvoiceType type);

    List<Invoice> findByPaymentIdInAndTypeIn(Collection<UUID> paymentIds, Collection<InvoiceType> types);

    /** A user's own documents, plus their family's when they can manage the family. */
    @Query("""
            SELECT i FROM Invoice i
            WHERE i.recipient.id = :userId OR (:ownerId IS NOT NULL AND i.householdOwner.id = :ownerId)
            ORDER BY i.issuedAt DESC
            """)
    List<Invoice> findVisibleTo(UUID userId, UUID ownerId);

    @Query(value = """
            SELECT i FROM Invoice i
            WHERE (:type IS NULL OR i.type = :type)
              AND (:query IS NULL
                   OR LOWER(i.number) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))
                   OR LOWER(i.recipientName) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))
                   OR LOWER(i.recipientEmail) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%')))
            ORDER BY i.issuedAt DESC
            """,
            countQuery = """
            SELECT COUNT(i) FROM Invoice i
            WHERE (:type IS NULL OR i.type = :type)
              AND (:query IS NULL
                   OR LOWER(i.number) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))
                   OR LOWER(i.recipientName) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))
                   OR LOWER(i.recipientEmail) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%')))
            """)
    Page<Invoice> adminSearch(InvoiceType type, String query, Pageable pageable);

    @Query(value = "SELECT nextval('receipt_number_seq')", nativeQuery = true)
    long nextReceiptNumber();

    @Query(value = "SELECT nextval('invoice_number_seq')", nativeQuery = true)
    long nextInvoiceNumber();

    @Query(value = "SELECT nextval('credit_note_number_seq')", nativeQuery = true)
    long nextCreditNoteNumber();

    @Query(value = "SELECT nextval('statement_number_seq')", nativeQuery = true)
    long nextStatementNumber();
}
