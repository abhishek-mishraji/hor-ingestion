package com.handsoffretail.gilbarco.filetypes.mcm.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * One MCM file as read: its MovementHeader and, for the store-level file (period 2), one row per department.
 * Amounts are as Passport writes them (promotions, discounts and refunds negative).
 */
public record McmReport(int primaryPeriod, LocalDate businessDate, LocalDateTime periodBegin, LocalDateTime periodEnd,
        String storeLocation, String vendorVersion, List<Department> departments) {

    /** PrimaryReportPeriod of the store-level file (the whole store, one block). */
    public static final int STORE_PERIOD = 2;
    /** PrimaryReportPeriod of the per-register / till file (a breakdown of the same close; not stored). */
    public static final int REGISTER_PERIOD = 98;

    public McmReport {
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(periodBegin, "periodBegin");
        Objects.requireNonNull(periodEnd, "periodEnd");
        departments = List.copyOf(departments);
    }

    /** One MCMDetail: MerchandiseCode, its description and MCMSalesTotals. */
    public record Department(int deptNumber, String deptName, BigDecimal salesAmount, BigDecimal salesQuantity,
            BigDecimal promotionAmount, int promotionCount, BigDecimal discountAmount, int discountCount,
            BigDecimal refundAmount, int refundCount, int transactionCount, BigDecimal openDeptSalesAmount,
            int openDeptTransactionCount) {
    }
}
