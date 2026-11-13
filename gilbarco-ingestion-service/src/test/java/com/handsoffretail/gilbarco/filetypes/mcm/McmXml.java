package com.handsoffretail.gilbarco.filetypes.mcm;

import java.time.LocalDate;

/**
 * Small synthetic MCM files in the exact shape Passport writes (NAXML-MovementReport 3.4, ISO-8859-1), for tests
 * that must not depend on the real Wolf data. Amounts are strings, as in the files ("-.74").
 */
public final class McmXml {

    private McmXml() {
    }

    /** MCM340yymmdd 0014 0n counter .xml: written just after midnight following {@code day}, as at the close. */
    public static String fileName(LocalDate day, int counter) {
        LocalDate written = day.plusDays(1);
        return String.format("MCM340%02d%02d%02d00140%d%d.xml", written.getYear() % 100, written.getMonthValue(),
                written.getDayOfMonth(), counter % 10, 1_600_000 + counter);
    }

    /** The store-level file (PrimaryReportPeriod 2): one MerchandiseCodeMovement block around the details. */
    public static String store(LocalDate day, String... details) {
        return report(movement(2, day, "", details));
    }

    /** The per-register file (PrimaryReportPeriod 98): one block per register, never stored. */
    public static String registers(LocalDate day, String... details) {
        return report(movement(98, day, """
                <SalesMovementHeader>
                <RegisterID>2</RegisterID>
                <CashierID>1</CashierID>
                <TillID>2514</TillID>
                </SalesMovementHeader>
                """, details));
    }

    /** A file of any PrimaryReportPeriod (for the refusal tests). */
    public static String period(int period, LocalDate day, String... details) {
        return report(movement(period, day, "", details));
    }

    /** One MCMDetail; promotion, discount and refund are written as given (Passport writes them negative). */
    public static String detail(int dept, String name, String sales, String qty, String promotion, String discount,
            String refund, int refundCount) {
        return """
                <MCMDetail>
                <MerchandiseCode>%d</MerchandiseCode>
                <MerchandiseCodeDescription>%s</MerchandiseCodeDescription>
                <MCMSalesTotals>
                <DiscountAmount>%s</DiscountAmount>
                <DiscountCount>%d</DiscountCount>
                <PromotionAmount>%s</PromotionAmount>
                <PromotionCount>%d</PromotionCount>
                <RefundAmount>%s</RefundAmount>
                <RefundCount>%d</RefundCount>
                <SalesQuantity>%s</SalesQuantity>
                <SalesAmount>%s</SalesAmount>
                <TransactionCount>%s</TransactionCount>
                <OpenDepartmentSalesAmount>0</OpenDepartmentSalesAmount>
                <OpenDepartmentTransactionCount>0</OpenDepartmentTransactionCount>
                </MCMSalesTotals>
                </MCMDetail>
                """.formatted(dept, name, discount, discount.equals("0") ? 0 : 1, promotion, promotion.equals("0") ? 0 : 1,
                refund, refundCount, qty, sales, qty);
    }

    /** A plain detail: sales only. */
    public static String detail(int dept, String name, String sales, String qty) {
        return detail(dept, name, sales, qty, "0", "0", "0", 0);
    }

    private static String movement(int period, LocalDate day, String salesHeader, String... details) {
        return """
                <MerchandiseCodeMovement>
                <MovementHeader>
                <ReportSequenceNumber>1</ReportSequenceNumber>
                <PrimaryReportPeriod>%d</PrimaryReportPeriod>
                <SecondaryReportPeriod>0</SecondaryReportPeriod>
                <BusinessDate>%s</BusinessDate>
                <BeginDate>%s</BeginDate>
                <BeginTime>00:00:51</BeginTime>
                <EndDate>%s</EndDate>
                <EndTime>00:11:36</EndTime>
                </MovementHeader>
                %s%s</MerchandiseCodeMovement>
                """.formatted(period, day, day, day.plusDays(1), salesHeader, String.join("", details));
    }

    private static String report(String body) {
        return """
                <?xml version="1.0" encoding="ISO-8859-1"?>
                <NAXML-MovementReport version="3.4">
                <TransmissionHeader>
                <StoreLocationID>13</StoreLocationID>
                <VendorName>Gilbarco-VeederRoot</VendorName>
                <VendorModelVersion>22.02.24.01</VendorModelVersion>
                </TransmissionHeader>
                %s</NAXML-MovementReport>
                """.formatted(body);
    }
}
