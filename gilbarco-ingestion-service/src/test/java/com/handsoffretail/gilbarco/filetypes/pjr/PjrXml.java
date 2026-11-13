package com.handsoffretail.gilbarco.filetypes.pjr;

import java.time.LocalDate;

/**
 * Small synthetic PJR files in the exact shape Passport writes (NAXML-POSJournal 3.4, ISO-8859-1, one event per
 * file), for tests that must not depend on the real Wolf data. Amounts are strings, as in the files.
 */
public final class PjrXml {

    private PjrXml() {
    }

    /** PJR340yymmdd hhmmss counter .xml, written on {@code day} at {@code 10:00:<n % 60>}. */
    public static String fileName(LocalDate day, int counter) {
        return String.format("PJR340%02d%02d%02d1000%02d%d.xml", day.getYear() % 100, day.getMonthValue(),
                day.getDayOfMonth(), counter % 60, 1_000_000 + counter);
    }

    /** A whole file around one event element. */
    public static String journal(LocalDate shiftDay, String event) {
        return """
                <?xml version ="1.0" encoding="ISO-8859-1" standalone="no"?>
                 <NAXML-POSJournal version="3.4" release="3.4.0">
                  <TransmissionHeader>
                   <StoreLocationID>13</StoreLocationID>
                   <VendorName>Gilbarco-VeederRoot</VendorName>
                   <VendorModelVersion>22.02.24.01</VendorModelVersion>
                  </TransmissionHeader>
                  <JournalReport>
                   <JournalHeader>
                    <ReportSequenceNumber>2412</ReportSequenceNumber>
                    <BeginDate>%s</BeginDate>
                    <BeginTime>06:00:00</BeginTime>
                    <EndDate>2100-01-01</EndDate>
                    <EndTime>00:00:00</EndTime>
                   </JournalHeader>
                %s
                  </JournalReport>
                 </NAXML-POSJournal>
                """.formatted(shiftDay, event);
    }

    /** A SaleEvent, RefundEvent or VoidEvent with its lines and summary. */
    public static String transaction(String type, LocalDate day, int register, long txn, boolean outside, boolean suspended,
            String grand, String direction, String... lines) {
        return """
                   <%1$s>
                    <TrainingModeFlag value="no"/>
                    <CashierID>1</CashierID>
                    <RegisterID>%3$d</RegisterID>
                    <TillID>2394</TillID>
                    <OutsideSalesFlag value="%5$s"/>
                    <TransactionID>%4$d</TransactionID>
                    <EventStartDate>%2$s</EventStartDate>
                    <EventStartTime>12:57:35</EventStartTime>
                    <EventEndDate>%2$s</EventEndDate>
                    <EventEndTime>12:57:37</EventEndTime>
                    <BusinessDate>%2$s</BusinessDate>
                    <ReceiptDate>%2$s</ReceiptDate>
                    <ReceiptTime>12:57:37</ReceiptTime>
                    <OfflineFlag value="no"/>
                    <SuspendFlag value="%6$s"/>
                    <TransactionDetailGroup>
                %7$s
                    </TransactionDetailGroup>
                    <TransactionSummary>
                     <TransactionTotalGrandAmount direction="%9$s">%8$s</TransactionTotalGrandAmount>
                    </TransactionSummary>
                   </%1$s>
                """.formatted(type, day, register, txn, outside ? "yes" : "no", suspended ? "yes" : "no",
                String.join("\n", lines), grand, direction);
    }

    public static String item(String status, int dept, String upc, String description, String regularPrice, String qty,
            String salesAmount, String promotion) {
        return line(status, """
                <ItemLine>
                 <ItemCode><POSCodeFormat format="upcA"/><POSCode>%s</POSCode></ItemCode>
                 <MerchandiseCode>%d</MerchandiseCode>
                 <Description>%s</Description>
                 <EntryMethod method="scan"/>
                 <ActualSalesPrice>%s</ActualSalesPrice>
                 <RegularSellPrice>%s</RegularSellPrice>
                 <SalesQuantity>%s</SalesQuantity>
                 <SalesAmount>%s</SalesAmount>
                 %s
                 <ItemTax><TaxLevelID>101</TaxLevelID></ItemTax>
                </ItemLine>""".formatted(upc, dept, description, regularPrice, regularPrice, qty, salesAmount,
                promotion == null ? "" : "<Promotion><PromotionAmount>" + promotion
                        + "</PromotionAmount><PromotionReason>combo</PromotionReason></Promotion>"));
    }

    /** A department ring (MerchandiseCodeLine): its Description names the department. */
    public static String ring(String status, int dept, String name, String qty, String salesAmount) {
        return line(status, """
                <MerchandiseCodeLine>
                 <MerchandiseCode>%d</MerchandiseCode>
                 <Description>%s</Description>
                 <SalesQuantity>%s</SalesQuantity>
                 <SalesAmount>%s</SalesAmount>
                </MerchandiseCodeLine>""".formatted(dept, name, qty, salesAmount));
    }

    public static String fuel(String grade, String description, String gallons, String salesAmount, String promotion) {
        return line("normal", """
                <FuelLine>
                 <FuelGradeID>%s</FuelGradeID>
                 <FuelPositionID>3</FuelPositionID>
                 <PriceTierCode>0001</PriceTierCode>
                 <ServiceLevelCode>1</ServiceLevelCode>
                 <Description>%s</Description>
                 <MerchandiseCode>1024</MerchandiseCode>
                 <RegularSellPrice>3.000</RegularSellPrice>
                 <SalesQuantity>%s</SalesQuantity>
                 <SalesAmount>%s</SalesAmount>
                 <Promotion><PromotionAmount>%s</PromotionAmount></Promotion>
                </FuelLine>""".formatted(grade, description, gallons, salesAmount, promotion));
    }

    public static String prepay(String status, String amount) {
        return line(status, "<FuelPrepayLine><FuelPositionID>5</FuelPositionID><SalesAmount>" + amount
                + "</SalesAmount></FuelPrepayLine>");
    }

    public static String tax(String taxable, String collected) {
        return line("normal", "<TransactionTax><TaxLevelID>101</TaxLevelID><TaxableSalesAmount>" + taxable
                + "</TaxableSalesAmount><TaxCollectedAmount>" + collected + "</TaxCollectedAmount></TransactionTax>");
    }

    public static String tender(String code, String sub, String amount) {
        return line("normal", "<TenderInfo><Tender><TenderCode>" + code + "</TenderCode><TenderSubCode>" + sub
                + "</TenderSubCode></Tender><TenderAmount>" + amount + "</TenderAmount><ChangeFlag value=\"no\"/></TenderInfo>");
    }

    public static String registerOpen(LocalDate day, long txn, String cash) {
        return other(day, txn, "<RegisterDetail detailType=\"open\"><CashInDrawer>" + cash + "</CashInDrawer></RegisterDetail>");
    }

    public static String noSale(LocalDate day, long txn) {
        return other(day, txn, "<AlertDetail><AlertCode>CashDrawerOpen</AlertCode><Description>NoSale</Description></AlertDetail>");
    }

    public static String safeDrop(LocalDate day, long txn, String amount) {
        return """
                   <FinancialEvent>
                    <RegisterID>2</RegisterID><TransactionID>%d</TransactionID><BusinessDate>%s</BusinessDate>
                    <FinancialEventDetail><SafeDropDetail><DropAmount>%s</DropAmount></SafeDropDetail></FinancialEventDetail>
                   </FinancialEvent>""".formatted(txn, day, amount);
    }

    /** A pay-in, in the shape seen from October 2026 (the AccountInfo names are never read). */
    public static String payIn(LocalDate day, long txn, String amount) {
        return """
                   <FinancialEvent>
                    <RegisterID>2</RegisterID><TransactionID>%d</TransactionID><BusinessDate>%s</BusinessDate>
                    <FinancialEventDetail status="normal"><PayInDetail><DetailAmount>%s</DetailAmount>
                     <TenderInfo><Tender><TenderCode>cash</TenderCode><TenderSubCode>generic</TenderSubCode></Tender>
                      <TenderAmount>%s</TenderAmount><ChangeFlag value="no"/></TenderInfo>
                     <AccountInfo><AccountID>7</AccountID><AccountName>A PERSON</AccountName></AccountInfo>
                     <PayInReason>House payment</PayInReason></PayInDetail></FinancialEventDetail>
                   </FinancialEvent>""".formatted(txn, day, amount, amount);
    }

    private static String other(LocalDate day, long txn, String detail) {
        return """
                   <OtherEvent>
                    <RegisterID>2</RegisterID><TransactionID>%d</TransactionID><BusinessDate>%s</BusinessDate>
                    %s
                   </OtherEvent>""".formatted(txn, day, detail);
    }

    private static String line(String status, String body) {
        return "     <TransactionLine status=\"" + status + "\">\n" + body + "\n     </TransactionLine>";
    }
}
