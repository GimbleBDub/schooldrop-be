package com.gimble.bus_stop.controllers;

import com.gimble.bus_stop.dto.billing.BillingDtos.*;
import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.services.BillingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Families (owner, guardians) and drivers: what they paid, earned and were charged, with their documents. */
@RestController
@RequestMapping("/v1/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;

    @GetMapping("/summary")
    public BillingSummary summary(@AuthenticationPrincipal User user) {
        return billingService.summary(user);
    }

    @GetMapping("/transactions")
    public List<Transaction> transactions(@AuthenticationPrincipal User user) {
        return billingService.transactions(user);
    }

    @GetMapping("/invoices")
    public List<InvoiceSummary> invoices(@AuthenticationPrincipal User user) {
        return billingService.invoices(user);
    }

    @GetMapping("/invoices/{invoiceId}")
    public InvoiceDetail invoice(@AuthenticationPrincipal User user, @PathVariable UUID invoiceId) {
        return billingService.invoice(user, invoiceId);
    }

    /** A signed link (relative to the /v1 base) that opens the PDF in a browser for 10 minutes. */
    @PostMapping("/invoices/{invoiceId}/link")
    public DownloadLink link(@AuthenticationPrincipal User user, @PathVariable UUID invoiceId) {
        return billingService.downloadLink(user, invoiceId);
    }

    /** Public path: either the bearer token or a signed link's expires + sig authorises it. */
    @GetMapping("/invoices/{invoiceId}/pdf")
    public ResponseEntity<byte[]> pdf(@AuthenticationPrincipal User user, @PathVariable UUID invoiceId,
                                      @RequestParam(required = false) Long expires,
                                      @RequestParam(required = false) String sig,
                                      @RequestParam(defaultValue = "false") boolean download) {
        BillingService.Pdf pdf = user != null && sig == null
                ? billingService.pdf(user, invoiceId)
                : billingService.pdfFromLink(invoiceId, expires != null ? expires : 0, sig);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(pdf.filename()).build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(pdf.bytes());
    }
}
