package com.handsoffretail.verifone.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.handsoffretail.verifone.parse.handlers.CpjParser;
import com.handsoffretail.verifone.parse.model.CashierEvent;
import com.handsoffretail.verifone.parse.model.CpjFileHeader;
import com.handsoffretail.verifone.parse.model.CpjFileTrailer;
import com.handsoffretail.verifone.parse.model.CpjSink;
import com.handsoffretail.verifone.parse.model.ParseFacts;
import com.handsoffretail.verifone.parse.model.Ticket;

/** Parser rules on small synthetic files (no real data, no Spring). */
class CpjParserRulesTest {

    private static final String TOTALS = "<insideSales>1.00</insideSales><insideGrand>1.00</insideGrand><outsideSales>0.00</outsideSales>"
            + "<outsideGrand>0.00</outsideGrand><attendantInsideSales>0.00</attendantInsideSales><attendantInsideGrand>0.00</attendantInsideGrand>"
            + "<attendantOutsideSales>0.00</attendantOutsideSales><attendantOutsideGrand>0.00</attendantOutsideGrand>"
            + "<overallSales>%s</overallSales><overallGrand>%s</overallGrand>";
    private static final String HEADER = "<trHeader><termMsgSN type=\"FINANCIAL\" term=\"101\">5</termMsgSN>"
            + "<trTickNum><posNum>101</posNum><trSeq>9000001</trSeq></trTickNum>"
            + "<period level=\"0\" name=\"HOUR\" seq=\"10\"/><period level=\"1\" name=\"Shift\" seq=\"2\"/><period level=\"2\" name=\"Day\" seq=\"1\"/>"
            + "<date>2026-03-01T10:00:00-05:00</date><duration>3</duration><till>1</till>"
            + "<cashier sysid=\"1\" empNum=\"1\" posNum=\"101\" period=\"1\" drawer=\"1\">ANN</cashier>"
            + "<storeNumber>AB1</storeNumber><uniqueID>u-1</uniqueID><physicalRegisterID>101</physicalRegisterID></trHeader>";
    private static final String LINE = "<trLine type=\"dept\"><trlTaxes/><trlFlags/><trlDept number=\"5\" type=\"norm\">GROCERY</trlDept>"
            + "<trlNetwCode>400</trlNetwCode><trlQty>1</trlQty><trlSign>1.00</trlSign><trlUnitPrice>1.00</trlUnitPrice>"
            + "<trlLineTot>1.00</trlLineTot><trlDesc>GROCERY</trlDesc></trLine>";
    private static final String VALUE = "<trValue><trCurrTot locale=\"USD\">1.00</trCurrTot><trTotNoTax>1.00</trTotNoTax>"
            + "<trTotTax>0.00</trTotTax><trTotWTax>1.00</trTotWTax><trSTotalizer>1.00</trSTotalizer><trGTotalizer>1.00</trGTotalizer><trTax/>%s</trValue>";

    @Test
    void aMinimalDayParsesIntoHeaderTicketAndTrailer() {
        Sink sink = new Sink();
        ParseFacts facts = parse(file(sale("", "")), sink);

        assertThat(sink.header.daySeq()).isEqualTo(7);
        assertThat(sink.tickets).singleElement().satisfies(t -> {
            assertThat(t.transType()).isEqualTo("sale");
            assertThat(t.posNum()).isEqualTo(101);
            assertThat(t.lines()).singleElement().satisfies(l -> {
                assertThat(l.department().name()).isEqualTo("GROCERY");
                assertThat(l.posDescription()).as("same as the department name: not stored").isEmpty();
            });
        });
        assertThat(sink.trailer).isNotNull();
        assertThat(facts.storeNumberMismatchCount()).isZero();
    }

    @Test
    void anUnknownElementOrAttributeFailsNamingItsPath() {
        assertThatThrownBy(() -> parse(file(sale("<trSurprise>1</trSurprise>", "")), new Sink()))
                .isInstanceOf(UnmappedInputException.class).hasMessage("unmapped element transSet/trans/trValue/trSurprise");
        assertThatThrownBy(() -> parse(file(sale("", "").replace("<trLine type=\"dept\">", "<trLine type=\"dept\" odd=\"1\">")), new Sink()))
                .isInstanceOf(UnmappedInputException.class).hasMessage("unmapped attribute transSet/trans/trLines/trLine/@odd");
        assertThatThrownBy(() -> parse(file(sale("", "").replace("<trlQty>1</trlQty>", "<trlQty>1</trlQty><trlQty>2</trlQty>")), new Sink()))
                .isInstanceOf(CpjFormatException.class).hasMessageContaining("trlQty appears more than once");
    }

    @Test
    void unknownTypesFail() {
        assertThatThrownBy(() -> parse(file(sale("", "").replace("type=\"sale\"", "type=\"layaway\"")), new Sink()))
                .isInstanceOf(CpjFormatException.class).hasMessageContaining("unknown transaction type 'layaway'");
        assertThatThrownBy(() -> parse(file(sale("", "").replace("type=\"dept\"", "type=\"bonus\"")), new Sink()))
                .isInstanceOf(CpjFormatException.class).hasMessageContaining("unknown line type 'bonus'");
        assertThatThrownBy(() -> parse(file(journal("NEW KIND", "")), new Sink()))
                .isInstanceOf(CpjFormatException.class).hasMessageContaining("unknown journal text type 'NEW KIND'");
    }

    @Test
    void skippedJournalTextMustHaveTheKnownShape() {
        Sink sink = new Sink();
        ParseFacts facts = parse(file(journal("SECURE USER", "")), sink);
        assertThat(facts.skippedJournal()).containsEntry("SECURE USER", 1);
        assertThat(sink.events).isEmpty();

        assertThatThrownBy(() -> parse(file(journal("SECURE USER", LINE)), new Sink()))
                .isInstanceOf(UnmappedInputException.class).hasMessageContaining("trJournal/trLine");
    }

    @Test
    void keptCashierEventsCarryTheirHeader() {
        Sink sink = new Sink();
        parse(file(journal("CHANGE QTY", "").replace("journal text", "CHANGE QTY COKE 20OZ FROM 1 TO 2")), sink);

        assertThat(sink.events).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo("CHANGE_QTY");
            assertThat(e.quantityChange()).hasValueSatisfying(q -> assertThat(q.itemDescription()).isEqualTo("COKE 20OZ"));
            assertThat(e.durationSec()).contains(3);
            assertThat(e.shiftSeq()).contains(2);
            assertThat(e.terminalNo()).contains(101);
            assertThat(e.cashierPeriod()).contains(1);
        });
    }

    @Test
    void aCouponTenderKeepsItsEntryMethod() {
        String payline = "<trPaylines><trPayline type=\"sale\" sysid=\"1\" locale=\"US DOLLAR\">"
                + "<trpPaycode mop=\"11\" cat=\"7\" nacstendercode=\"generic\" nacstendersubcode=\"generic\">COUPON</trpPaycode>"
                + "<trpAmt>1.00</trpAmt><trpCouponEntryMethod>KEY</trpCouponEntryMethod></trPayline></trPaylines>";
        Sink sink = new Sink();
        parse(file(sale("", payline)), sink);

        assertThat(sink.tickets.get(0).payments()).singleElement().satisfies(p -> {
            assertThat(p.paymentType().paycode()).isEqualTo("COUPON");
            assertThat(p.couponEntryMethod()).contains("KEY");
            assertThat(p.card()).isEmpty();
        });
    }

    @Test
    void storeNumberDifferentFromTheSiteIsCountedForTheLoadCheck() {
        ParseFacts facts = parse(file(sale("", "")).replace("site=\"AB1\"", "site=\"ZZ9\""), new Sink());

        assertThat(facts.storeNumberMismatchCount()).isEqualTo(1);
    }

    @Test
    void birthDatesTakeTheCenturyThatGivesThePosAge() {
        LocalDate ticketDay = LocalDate.parse("2026-10-11");

        assertThat(DobDecoder.decode("011505", 21, ticketDay, "p")).isEqualTo(LocalDate.parse("2005-01-15"));
        assertThat(DobDecoder.decode("011560", 66, ticketDay, "p")).isEqualTo(LocalDate.parse("1960-01-15"));
        assertThatThrownBy(() -> DobDecoder.decode("011560", 30, ticketDay, "path/custDOB"))
                .isInstanceOf(CpjFormatException.class).hasMessageContaining("age 30").hasMessageNotContaining("011560");
        assertThatThrownBy(() -> DobDecoder.decode("13", 30, ticketDay, "p")).isInstanceOf(CpjFormatException.class);
    }

    @Test
    void tooManyDecimalPlacesAreRefusedNotRounded() {
        assertThatThrownBy(() -> parse(file(sale("", "").replace("<trlLineTot>1.00</trlLineTot>", "<trlLineTot>1.005</trlLineTot>")), new Sink()))
                .isInstanceOf(CpjFormatException.class).hasMessageContaining("more than 2 decimal places");
    }

    private static ParseFacts parse(String xml, CpjSink sink) {
        return CpjParser.standard().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), sink);
    }

    private static String file(String trans) {
        return "<transSet periodID=\"2\" periodname=\"Day\" longId=\"2026-03-01T06:00:00-05:00\" shortId=\"7\" site=\"AB1\">"
                + "<openedTime>2026-03-01T06:00:00-05:00</openedTime><closedTime>2026-03-02T06:00:00-05:00</closedTime>"
                + "<startTotals>" + String.format(TOTALS, "100.00", "100.00") + "</startTotals>" + trans
                + "<endTotals>" + String.format(TOTALS, "101.00", "101.00") + "</endTotals></transSet>";
    }

    private static String sale(String extraValue, String extraTrans) {
        return "<trans type=\"sale\" recalled=\"false\">" + HEADER + String.format(VALUE, extraValue) + "<trLines>" + LINE
                + "</trLines>" + extraTrans + "</trans>";
    }

    private static String journal(String kind, String extra) {
        return "<trans type=\"journal\"><trHeader><termMsgSN type=\"JOURNAL\" term=\"101\">6</termMsgSN>"
                + "<period level=\"0\" name=\"HOUR\" seq=\"10\"/><period level=\"1\" name=\"Shift\" seq=\"2\"/><period level=\"2\" name=\"Day\" seq=\"1\"/>"
                + "<date>2026-03-01T10:00:00-05:00</date><duration>3</duration><posNum>101</posNum>"
                + "<cashier sysid=\"1\" empNum=\"1\" posNum=\"101\" period=\"1\" drawer=\"1\">ANN</cashier>"
                + "<storeNumber>AB1</storeNumber><uniqueID>u-2</uniqueID></trHeader>"
                + "<trJournal><trjText type=\"" + kind + "\">journal text</trjText><marker/>" + extra + "</trJournal></trans>";
    }

    private static final class Sink implements CpjSink {
        CpjFileHeader header;
        final List<Ticket> tickets = new ArrayList<>();
        final List<CashierEvent> events = new ArrayList<>();
        CpjFileTrailer trailer;

        @Override
        public void begin(CpjFileHeader h) {
            header = h;
        }

        @Override
        public void ticket(Ticket t) {
            tickets.add(t);
        }

        @Override
        public void cashierEvent(CashierEvent e) {
            events.add(e);
        }

        @Override
        public void end(CpjFileTrailer t) {
            trailer = t;
        }
    }
}
