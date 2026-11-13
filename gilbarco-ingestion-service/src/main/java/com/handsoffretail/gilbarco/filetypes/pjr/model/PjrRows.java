package com.handsoffretail.gilbarco.filetypes.pjr.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Rows produced from one PJR file, in the shape of the pjr tables (ported from pjr-platform Rows). */
public final class PjrRows {

    private PjrRows() {
    }

    /** The one event of a file (SaleEvent, RefundEvent, VoidEvent, OtherEvent or FinancialEvent). */
    public record Event(String eventType, String detailKind, LocalDate businessDate, Long transactionId,
            Integer registerId, String cashierId, String tillId,
            LocalDateTime startedAt, LocalDateTime endedAt, LocalDateTime receiptAt,
            boolean outside, boolean suspended, boolean training, boolean offline,
            Integer linkedRegisterId, Long linkedTransactionId, String linkReason, String loyaltyProgram,
            BigDecimal totalGross, BigDecimal totalNet, BigDecimal totalTax, BigDecimal totalGrand,
            String grandDirection, BigDecimal detailAmount, String payee, String alertCode, String reason) {

        public Event {
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(detailKind, "detailKind");
            Objects.requireNonNull(businessDate, "businessDate");
        }
    }

    /** ItemLine, MerchandiseCodeLine, FuelLine or FuelPrepayLine. */
    public record Line(int lineNo, String status, String lineType, Integer deptNumber,
            String posCode, String posCodeFormat, String posCodeModifier, String description, String entryMethod,
            BigDecimal quantity, BigDecimal sellingUnits, BigDecimal actualPrice, BigDecimal regularPrice,
            BigDecimal salesAmount, BigDecimal promotionAmount, BigDecimal discountAmount, String promotionReasons,
            String taxLevel, String fuelGradeId, Integer fuelPosition, String priceTier, String serviceLevel) {
    }

    /** One TenderInfo line. Card numbers and approval codes are never read. */
    public record Tender(int lineNo, String status, String tenderCode, String tenderSub, BigDecimal amount,
            boolean change, String provider, BigDecimal authorizedAmount) {
    }

    /** One TransactionTax line. */
    public record Tax(int lineNo, String status, String taxLevel, BigDecimal taxable, BigDecimal collected,
            BigDecimal taxableRefunded, BigDecimal taxRefunded, BigDecimal exempt, BigDecimal forgivenSales) {
    }

    /** Everything in one file. shiftBegin = JournalHeader BeginDate/BeginTime: the start of the current shift. */
    public record ParsedFile(String storeLocation, String vendorVersion, LocalDateTime shiftBegin,
            Event event, List<Line> lines, List<Tender> tenders, List<Tax> taxes) {

        public ParsedFile {
            Objects.requireNonNull(event, "event");
            lines = List.copyOf(lines);
            tenders = List.copyOf(tenders);
            taxes = List.copyOf(taxes);
        }
    }
}
