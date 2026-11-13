package com.handsoffretail.gilbarco.filetypes.pjr;

import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.item;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.journal;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.ring;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.tax;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.tender;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.handsoffretail.gilbarco.TestData;
import com.handsoffretail.gilbarco.filetypes.pjr.model.PjrRows;
import com.handsoffretail.gilbarco.filetypes.pjr.parser.PjrFileName;
import com.handsoffretail.gilbarco.filetypes.pjr.parser.PjrFormatException;
import com.handsoffretail.gilbarco.filetypes.pjr.parser.PjrParser;

/** The ported PJR parser on synthetic files of every event kind, and on every real Wolf file when present. */
class PjrParserTest {

    private static final LocalDate DAY = LocalDate.parse("2026-09-23");

    private final PjrParser parser = new PjrParser();

    @Test
    void aSaleWithPromotionTaxAndTender() {
        PjrRows.ParsedFile f = parse(journal(DAY, transaction("SaleEvent", DAY, 2, 4508, false, false, "2.16", "Collected",
                item("normal", 1, "012345678905", "COLA", "2.50", "1", "2.00", "-0.50"),
                tax("2.00", "0.16"), tender("cash", "generic", "2.16"))));

        PjrRows.Event e = f.event();
        assertThat(f.storeLocation()).isEqualTo("13");
        assertThat(f.shiftBegin()).isEqualTo(LocalDateTime.parse("2026-09-23T06:00:00"));
        assertThat(e.eventType()).isEqualTo("SaleEvent");
        assertThat(e.detailKind()).isEqualTo("sale");
        assertThat(e.transactionId()).isEqualTo(4508L);
        assertThat(e.registerId()).isEqualTo(2);
        assertThat(e.suspended()).isFalse();
        assertThat(e.totalGrand()).isEqualByComparingTo("2.16");
        assertThat(e.grandDirection()).isEqualTo("Collected");

        PjrRows.Line line = f.lines().getFirst();
        assertThat(line.lineNo()).isEqualTo(1);
        assertThat(line.lineType()).isEqualTo("ItemLine");
        assertThat(line.posCodeFormat()).isEqualTo("upcA");
        assertThat(line.entryMethod()).isEqualTo("scan");
        assertThat(line.salesAmount()).isEqualByComparingTo("2.00");
        assertThat(line.promotionAmount()).isEqualByComparingTo("-0.50");
        assertThat(line.promotionReasons()).isEqualTo("combo");
        // department gross = SalesAmount - PromotionAmount - DiscountAmount (computed in the database)
        assertThat(line.salesAmount().subtract(line.promotionAmount()).subtract(line.discountAmount()))
                .isEqualByComparingTo("2.50");
        assertThat(f.taxes()).singleElement().satisfies(t -> {
            assertThat(t.lineNo()).isEqualTo(2);
            assertThat(t.collected()).isEqualByComparingTo("0.16");
        });
        assertThat(f.tenders()).singleElement().satisfies(t -> {
            assertThat(t.lineNo()).isEqualTo(3);
            assertThat(t.tenderCode() + "/" + t.tenderSub()).isEqualTo("cash/generic");
        });
    }

    @Test
    void suspendedVoidAndRefundEventsAreReadAsTheyAre() {
        assertThat(parse(journal(DAY, transaction("SaleEvent", DAY, 2, 1, false, true, "20", "Collected",
                PjrXml.prepay("normal", "20"), tender("creditCards", "generic", "20")))).event().suspended()).isTrue();
        assertThat(parse(journal(DAY, transaction("VoidEvent", DAY, 2, 2, false, false, "3.24", "Collected",
                item("normal", 1, "1", "X", "3.00", "1", "3.00", null)))).event().detailKind()).isEqualTo("void");
        PjrRows.ParsedFile refund = parse(journal(DAY, transaction("RefundEvent", DAY, 2, 3, false, false, "-50", "Refunded",
                ring("normal", 6, "LOTTO PAID OUT", "-1", "-50"), tender("cash", "generic", "-50"))));
        assertThat(refund.event().detailKind()).isEqualTo("refund");
        assertThat(refund.lines().getFirst().deptNumber()).isEqualTo(6);
        assertThat(refund.lines().getFirst().description()).isEqualTo("LOTTO PAID OUT");
    }

    @Test
    void cashEventsCarryTheirAmount() {
        assertThat(parse(journal(DAY, PjrXml.registerOpen(DAY, 1, "150.00"))).event())
                .satisfies(e -> {
                    assertThat(e.detailKind()).isEqualTo("register_open");
                    assertThat(e.detailAmount()).isEqualByComparingTo("150");
                });
        assertThat(parse(journal(DAY, PjrXml.safeDrop(DAY, 2, "200"))).event().detailKind()).isEqualTo("safe_drop");
        assertThat(parse(journal(DAY, PjrXml.noSale(DAY, 3))).event().detailKind()).isEqualTo("no_sale");
        assertThat(parse(journal(DAY, PjrXml.payIn(DAY, 4, "25.00"))).event()).satisfies(e -> {
            assertThat(e.detailKind()).isEqualTo("pay_in");
            assertThat(e.detailAmount()).isEqualByComparingTo("25");
            assertThat(e.reason()).isEqualTo("House payment");
            assertThat(e.payee()).as("a pay-in's account name may be a person: never read").isNull();
        });
    }

    @Test
    void anythingNotInTheEvidenceIsRefusedNamingTheFile() {
        assertRefused(journal(DAY, "<FooEvent><BusinessDate>2026-09-23</BusinessDate></FooEvent>"), "unknown event type FooEvent");
        assertRefused(journal(DAY, PjrXml.noSale(DAY, 1) + PjrXml.noSale(DAY, 2)), "exactly one event");
        assertRefused(journal(DAY, transaction("SaleEvent", DAY, 2, 1, false, false, "1", "Collected",
                "<TransactionLine status=\"normal\"><GiftLine/></TransactionLine>")), "unknown line type GiftLine");
        assertRefused(journal(DAY, "<SaleEvent><TransactionID>1</TransactionID></SaleEvent>"), "no BusinessDate");
        assertRefused("<transSet/>", "not a PJR journal");
        assertRefused("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///c:/x\">]><x>&e;</x>", "not valid XML");
        assertRefused(journal(DAY, transaction("SaleEvent", DAY, 2, 1, false, false, "abc", "Collected")), "NumberFormatException");
    }

    @Test
    void fileNamesGiveTheWriteTimeAndCounter() {
        PjrFileName name = PjrFileName.of("PJR3402609230004181533189.xml");
        assertThat(name.writtenAt()).contains(LocalDateTime.parse("2026-09-23T00:04:18"));
        assertThat(name.counter()).contains(1533189L);
        assertThat(PjrFileName.of("PJR123.xml").writtenAt()).isEmpty();
        assertThatThrownBy(() -> PjrFileName.of("MCM3402609230004181533189.xml")).isInstanceOf(PjrFormatException.class);
    }

    /** Every real Wolf file parses, and the event mix matches POS_XML_FILE_TYPES.md section 3.2 for 23-24 Sep. */
    @Test
    void everyRealWolfFileParses() throws IOException {
        TestData data = TestData.require();
        Map<String, Integer> types = new TreeMap<>();
        int parsed = 0;
        for (List<Path> day : data.pjrByBusinessDate().values()) {
            for (Path file : day) {
                try (InputStream in = Files.newInputStream(file)) {
                    PjrRows.ParsedFile f = parser.parse(in, file.getFileName().toString());
                    types.merge(f.event().eventType(), 1, Integer::sum);
                    parsed++;
                }
            }
        }
        assertThat(parsed).isEqualTo(data.files()).isEqualTo(6618);
        assertThat(types).containsOnlyKeys("SaleEvent", "RefundEvent", "VoidEvent", "OtherEvent", "FinancialEvent");
    }

    private PjrRows.ParsedFile parse(String xml) {
        return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.ISO_8859_1)), "PJR1.xml");
    }

    private void assertRefused(String xml, String reason) {
        assertThatThrownBy(() -> parse(xml)).isInstanceOf(PjrFormatException.class)
                .hasMessageStartingWith("PJR1.xml: ").hasMessageContaining(reason);
    }
}
