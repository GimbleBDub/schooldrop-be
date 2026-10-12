package com.gimble.bus_stop.billing;

import com.gimble.bus_stop.entities.Invoice;
import com.gimble.bus_stop.entities.InvoiceLine;
import com.gimble.bus_stop.enums.InvoiceType;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Renders a billing document as an A4 PDF. */
@Component
@RequiredArgsConstructor
public class InvoicePdf {

    private static final Color BRAND = new Color(0x23, 0x47, 0xD9);
    private static final Color INK = new Color(0x0F, 0x17, 0x2A);
    private static final Color MUTED = new Color(0x64, 0x74, 0x8B);
    private static final Color BORDER = new Color(0xE3, 0xE7, 0xEF);
    private static final Color SOFT = new Color(0xF6, 0xF7, 0xFB);
    private static final Color GREEN = new Color(0x15, 0x80, 0x3D);
    private static final Color RED = new Color(0xDC, 0x26, 0x26);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private final BillingSettings settings;
    private final Clock clock;

    public static String title(Invoice invoice, boolean vatRegistered) {
        return switch (invoice.getType()) {
            case RECEIPT -> "Receipt";
            case INVOICE -> vatRegistered ? "Tax invoice" : "Invoice";
            case CREDIT_NOTE -> "Credit note";
            case STATEMENT -> "Payout statement";
        };
    }

    public byte[] render(Invoice invoice) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 48, 56);
        PdfWriter.getInstance(doc, out);
        doc.addTitle(title(invoice, settings.vatRegistered()) + " " + invoice.getNumber());
        doc.addCreator("SchoolDrop");
        doc.open();

        header(doc, invoice);
        parties(doc, invoice);
        lines(doc, invoice);
        totals(doc, invoice);
        details(doc, invoice);
        footer(doc);

        doc.close();
        return out.toByteArray();
    }

    // ─── Sections ────────────────────────────────────────────────────────────

    private void header(Document doc, Invoice invoice) {
        PdfPTable table = table(2, new float[]{1.2f, 1f});

        PdfPCell brand = cell();
        Paragraph logo = new Paragraph();
        logo.add(new Chunk("School", font(22, Font.BOLD, INK)));
        logo.add(new Chunk("Drop", font(22, Font.BOLD, BRAND)));
        brand.addElement(logo);
        brand.addElement(text(settings.getCompanyName(), 9, Font.BOLD, INK));
        brand.addElement(text(settings.getCompanyAddress(), 9, Font.NORMAL, MUTED));
        if (notBlank(settings.getCompanyRegistration())) {
            brand.addElement(text("Company reg. " + settings.getCompanyRegistration(), 9, Font.NORMAL, MUTED));
        }
        if (settings.vatRegistered()) {
            brand.addElement(text("VAT no. " + settings.getVatNumber(), 9, Font.NORMAL, MUTED));
        }
        brand.addElement(text(settings.getSupportEmail(), 9, Font.NORMAL, MUTED));
        table.addCell(brand);

        PdfPCell meta = cell();
        meta.setHorizontalAlignment(Element.ALIGN_RIGHT);
        meta.addElement(right(title(invoice, settings.vatRegistered()).toUpperCase(Locale.ENGLISH), 18, Font.BOLD, INK));
        meta.addElement(right(invoice.getNumber(), 11, Font.BOLD, BRAND));
        meta.addElement(right("Issued " + DATE.format(zoned(invoice.getIssuedAt())), 9, Font.NORMAL, MUTED));
        String badge = switch (invoice.getType()) {
            case CREDIT_NOTE -> "REFUNDED";
            case STATEMENT -> "PAID OUT";
            default -> invoice.isPaidDirectly() ? "PAID TO DRIVER" : "PAID";
        };
        meta.addElement(right(badge, 10, Font.BOLD, invoice.getType() == InvoiceType.CREDIT_NOTE ? RED : GREEN));
        table.addCell(meta);

        doc.add(table);
        doc.add(spacer(18));
    }

    private void parties(Document doc, Invoice invoice) {
        PdfPTable table = table(2, new float[]{1f, 1f});
        boolean statement = invoice.getType() == InvoiceType.STATEMENT;

        PdfPCell to = boxed();
        to.addElement(text(statement ? "PAID TO" : "BILLED TO", 8, Font.BOLD, MUTED));
        to.addElement(text(invoice.getRecipientName(), 11, Font.BOLD, INK));
        if (notBlank(invoice.getRecipientEmail())) {
            to.addElement(text(invoice.getRecipientEmail(), 9, Font.NORMAL, MUTED));
        }
        if (notBlank(invoice.getRecipientPhone())) {
            to.addElement(text(invoice.getRecipientPhone(), 9, Font.NORMAL, MUTED));
        }
        table.addCell(to);

        PdfPCell from = boxed();
        from.addElement(text(statement ? "FROM" : invoice.getType() == InvoiceType.RECEIPT ? "SERVICE BY" : "SUPPLIER", 8, Font.BOLD, MUTED));
        from.addElement(text(invoice.getSupplierName(), 11, Font.BOLD, INK));
        if (invoice.getType() == InvoiceType.RECEIPT && !invoice.isPaidDirectly()) {
            from.addElement(text("Collected by " + settings.getCompanyName(), 9, Font.NORMAL, MUTED));
        }
        if (invoice.getCreditedInvoice() != null) {
            from.addElement(text("Credits " + invoice.getCreditedInvoice().getNumber(), 9, Font.NORMAL, MUTED));
        }
        table.addCell(from);

        doc.add(table);
        doc.add(spacer(18));
    }

    private void lines(Document doc, Invoice invoice) {
        PdfPTable table = table(4, new float[]{5.2f, 0.8f, 1.6f, 1.6f});
        table.setHeaderRows(1);
        for (String h : new String[]{"Description", "Qty", "Unit price", "Amount"}) {
            PdfPCell c = new PdfPCell(new Phrase(h, font(8, Font.BOLD, MUTED)));
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(BORDER);
            c.setPaddingBottom(8);
            c.setHorizontalAlignment(h.equals("Description") ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            table.addCell(c);
        }
        for (InvoiceLine line : invoice.getLines()) {
            boolean deduction = line.getAmount().signum() < 0;
            Color color = deduction ? RED : INK;
            table.addCell(lineCell(line.getDescription(), Element.ALIGN_LEFT, color));
            table.addCell(lineCell(qty(line.getQuantity()), Element.ALIGN_RIGHT, color));
            table.addCell(lineCell(money(line.getUnitAmount(), invoice.getCurrency()), Element.ALIGN_RIGHT, color));
            table.addCell(lineCell(money(line.getAmount(), invoice.getCurrency()), Element.ALIGN_RIGHT, color));
        }
        doc.add(table);
        doc.add(spacer(10));
    }

    private void totals(Document doc, Invoice invoice) {
        PdfPTable table = table(2, new float[]{3f, 1.4f});
        table.setWidthPercentage(48);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        String currency = invoice.getCurrency();

        if (invoice.getType() == InvoiceType.STATEMENT) {
            totalRow(table, "Paid by families", money(invoice.getSubtotal(), currency), false);
            totalRow(table, "SchoolDrop commission", money(invoice.getSubtotal().subtract(invoice.getTotal()).negate(), currency), false);
            if (invoice.getTax().signum() > 0) {
                totalRow(table, "VAT included in commission", money(invoice.getTax(), currency), false);
            }
            totalRow(table, "Sent to you", money(invoice.getTotal(), currency), true);
        } else {
            if (invoice.getTax().signum() > 0) {
                totalRow(table, "Amount excl. VAT", money(invoice.getTotal().subtract(invoice.getTax()), currency), false);
                totalRow(table, "VAT (" + settings.getVatPercent().stripTrailingZeros().toPlainString() + "%)",
                        money(invoice.getTax(), currency), false);
            }
            String label = switch (invoice.getType()) {
                case CREDIT_NOTE -> "Refunded";
                case RECEIPT -> invoice.isPaidDirectly() ? "Paid to driver" : "Paid";
                default -> "Total paid";
            };
            totalRow(table, label, money(invoice.getTotal(), currency), true);
        }
        doc.add(table);
        doc.add(spacer(20));
    }

    private void details(Document doc, Invoice invoice) {
        PdfPTable table = table(1, new float[]{1f});
        PdfPCell box = boxed();
        box.addElement(text("PAYMENT DETAILS", 8, Font.BOLD, MUTED));
        if (invoice.getPaymentMethod() != null) {
            String method = invoice.isPaidDirectly() ? "Cash or bank transfer, paid to the driver"
                    : methodLabel(invoice.getPaymentMethod().name());
            box.addElement(text("Method: " + method, 9, Font.NORMAL, INK));
        }
        if (invoice.getPaidAt() != null) {
            String when = switch (invoice.getType()) {
                case CREDIT_NOTE -> "Refunded: ";
                case STATEMENT -> "Sent: ";
                default -> "Paid: ";
            };
            box.addElement(text(when + DATE_TIME.format(zoned(invoice.getPaidAt())), 9, Font.NORMAL, INK));
        }
        if (notBlank(invoice.getNotes())) {
            box.addElement(spacer(4));
            box.addElement(text(invoice.getNotes(), 9, Font.NORMAL, MUTED));
        }
        table.addCell(box);
        doc.add(table);
    }

    private void footer(Document doc) {
        doc.add(spacer(28));
        Paragraph p = new Paragraph("Questions about this document? Contact " + settings.getSupportEmail()
                + " and quote the number above. Amounts are in Botswana Pula (BWP).", font(8, Font.NORMAL, MUTED));
        p.setAlignment(Element.ALIGN_CENTER);
        doc.add(p);
    }

    // ─── Building blocks ─────────────────────────────────────────────────────

    private static PdfPTable table(int columns, float[] widths) {
        PdfPTable table = new PdfPTable(columns);
        table.setWidthPercentage(100);
        table.setWidths(widths);
        return table;
    }

    private static PdfPCell cell() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0);
        return c;
    }

    private static PdfPCell boxed() {
        PdfPCell c = new PdfPCell();
        c.setBorderColor(BORDER);
        c.setBackgroundColor(SOFT);
        c.setPadding(12);
        c.setPaddingTop(8);
        return c;
    }

    private static PdfPCell lineCell(String value, int align, Color color) {
        PdfPCell c = new PdfPCell(new Phrase(value, font(9, Font.NORMAL, color)));
        c.setLeading(0, 1.35f);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(BORDER);
        c.setPaddingTop(6);
        c.setPaddingBottom(8);
        c.setHorizontalAlignment(align);
        return c;
    }

    private static void totalRow(PdfPTable table, String label, String value, boolean strong) {
        Font f = strong ? font(11, Font.BOLD, INK) : font(9, Font.NORMAL, MUTED);
        PdfPCell l = new PdfPCell(new Phrase(label, f));
        PdfPCell v = new PdfPCell(new Phrase(value, strong ? font(11, Font.BOLD, INK) : font(9, Font.NORMAL, INK)));
        for (PdfPCell c : new PdfPCell[]{l, v}) {
            c.setBorder(strong ? Rectangle.TOP : Rectangle.NO_BORDER);
            c.setBorderColor(BORDER);
            c.setPaddingTop(strong ? 8 : 3);
            c.setPaddingBottom(3);
        }
        v.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(l);
        table.addCell(v);
    }

    private static Paragraph text(String value, float size, int style, Color color) {
        Paragraph p = new Paragraph(value, font(size, style, color));
        p.setLeading(size * 1.45f);
        return p;
    }

    private static Paragraph right(String value, float size, int style, Color color) {
        Paragraph p = text(value, size, style, color);
        p.setAlignment(Element.ALIGN_RIGHT);
        return p;
    }

    private static Paragraph spacer(float height) {
        Paragraph p = new Paragraph(" ", font(1, Font.NORMAL, Color.WHITE));
        p.setSpacingAfter(height);
        return p;
    }

    private static Font font(float size, int style, Color color) {
        return FontFactory.getFont(FontFactory.HELVETICA, size, style, color);
    }

    private ZonedDateTime zoned(Instant instant) {
        return instant.atZone(clock.getZone());
    }

    static String money(BigDecimal amount, String currency) {
        String prefix = "BWP".equals(currency) ? "P" : currency + " ";
        DecimalFormat f = new DecimalFormat("#,##0.00");
        return (amount.signum() < 0 ? "-" : "") + prefix + f.format(amount.abs());
    }

    private static String qty(BigDecimal q) {
        return q.stripTrailingZeros().toPlainString();
    }

    public static String methodLabel(String method) {
        return switch (method) {
            case "ORANGE_MONEY" -> "Orange Money";
            case "MYZAKA" -> "MyZaka";
            case "SMEGA" -> "Smega";
            case "CARD" -> "Card";
            case "BANK_TRANSFER" -> "Bank transfer";
            case "CASH" -> "Cash";
            default -> method;
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
