package com.gimble.bus_stop.config;

import com.gimble.bus_stop.services.InvoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Issues receipts, invoices and statements for money that moved before invoicing existed. Idempotent. */
@Component
@Order(100) // after the demo data seeder
@RequiredArgsConstructor
@Slf4j
public class InvoiceBackfill implements ApplicationRunner {

    private final InvoiceService invoiceService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int issued = invoiceService.backfill();
            if (issued > 0) {
                log.info("Issued {} missing billing documents", issued);
            }
        } catch (Exception e) {
            // Never block startup over paperwork; the next start tries again
            log.warn("Invoice backfill failed: {}", e.getMessage());
        }
    }
}
