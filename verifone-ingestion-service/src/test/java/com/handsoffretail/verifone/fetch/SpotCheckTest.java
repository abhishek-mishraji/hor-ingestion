package com.handsoffretail.verifone.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.junit.jupiter.api.Test;

import com.handsoffretail.verifone.GoldenDays;
import com.handsoffretail.verifone.IngestionIntegrationTest;
import com.handsoffretail.verifone.TestData;
import com.handsoffretail.verifone.TestStores;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.XmlElements;

/**
 * IMPLEMENTATION_PLAN.md Phase 4 spot check: 25 random transactions across the 20 days, read back
 * from the database, equal the XML field by field (type, register, ticket number, time, totals, each
 * line's department / item / quantity / amount, each payment's type and amount).
 */
class SpotCheckTest extends IngestionIntegrationTest {

    private static final int SAMPLES = 25;
    private static final long SEED = 20261018L;

    @Test
    void twentyFiveRandomTransactionsEqualTheXml() throws Exception {
        List<LocalDate> days = new ArrayList<>(GoldenDays.load().keySet());
        Random random = new Random(SEED);
        List<String> checked = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            LocalDate day = days.get(random.nextInt(days.size()));
            fetch(TestStores.TIKI, day, FetchRequest.Mode.REFUSE);
            List<Map<String, Object>> tickets = jdbc.queryForList("""
                    SELECT t.ticket_id, t.seq_in_file FROM cpj.tickets t JOIN cpj.cpj_files f USING (cpj_file_id)
                    WHERE f.store_id = ? AND f.business_date = ? ORDER BY t.seq_in_file""", TestStores.TIKI, day);
            Map<String, Object> pick = tickets.get(random.nextInt(tickets.size()));
            long ticketId = ((Number) pick.get("ticket_id")).longValue();
            int seq = ((Number) pick.get("seq_in_file")).intValue();
            compare(ticketId, xmlTrans(day, seq), day + " #" + seq);
            checked.add(day + " #" + seq);
        }
        assertThat(checked).hasSize(SAMPLES);
        System.out.println("spot-checked: " + checked);
    }

    private void compare(long ticketId, XmlElement trans, String where) {
        Map<String, Object> t = jdbc.queryForMap("""
                SELECT t.trans_type, r.pos_num, t.tr_seq, t.ticket_at, t.total, t.total_no_tax, t.total_tax
                FROM cpj.tickets t JOIN master.registers r USING (register_id) WHERE t.ticket_id = ?""", ticketId);
        XmlElement header = trans.requiredChild("trHeader");
        assertThat(t.get("trans_type")).as(where + " type").isEqualTo(trans.requiredAttr("type"));
        String pos = header.child("trTickNum").map(n -> n.requiredChildText("posNum")).orElseGet(() -> header.requiredChildText("posNum"));
        assertThat(t.get("pos_num")).as(where + " register").isEqualTo(Integer.parseInt(pos));
        header.child("trTickNum").ifPresent(n -> assertThat(t.get("tr_seq")).as(where + " ticket number")
                .isEqualTo(Long.parseLong(n.requiredChildText("trSeq"))));
        assertThat(((OffsetDateTime) jdbc.queryForObject("SELECT ticket_at FROM cpj.tickets WHERE ticket_id = ?",
                OffsetDateTime.class, ticketId)).toInstant()).as(where + " time")
                .isEqualTo(OffsetDateTime.parse(header.requiredChildText("date")).toInstant());
        trans.child("trValue").ifPresent(v -> {
            assertMoney(t.get("total"), v.childText("trCurrTot").orElse(null), where + " total");
            assertMoney(t.get("total_no_tax"), v.childText("trTotNoTax").orElse(null), where + " total no tax");
            assertMoney(t.get("total_tax"), v.childText("trTotTax").orElse(null), where + " total tax");
        });
        compareLines(ticketId, trans, where);
        comparePayments(ticketId, trans, where);
    }

    private void compareLines(long ticketId, XmlElement trans, String where) {
        List<XmlElement> lines = trans.child("trLines").map(l -> l.children("trLine")).orElse(List.of());
        List<Map<String, Object>> stored = jdbc.queryForList("""
                SELECT l.line_no, l.line_type, d.dept_number, i.upc, i.upc_modifier, l.quantity, l.line_total
                FROM cpj.ticket_lines l
                JOIN master.department_versions dv USING (department_version_id) JOIN master.departments d USING (department_id)
                LEFT JOIN master.item_versions iv USING (item_version_id) LEFT JOIN master.items i ON i.item_id = iv.item_id
                WHERE l.ticket_id = ? ORDER BY l.line_no""", ticketId);
        assertThat(stored).as(where + " line count").hasSize(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            XmlElement x = lines.get(i);
            Map<String, Object> s = stored.get(i);
            String w = where + " line " + (i + 1);
            assertThat(s.get("line_type")).as(w + " type").isEqualTo(x.requiredAttr("type"));
            assertThat(s.get("dept_number")).as(w + " department").isEqualTo(Integer.parseInt(x.requiredChild("trlDept").requiredAttr("number")));
            assertThat(s.get("upc")).as(w + " item").isEqualTo(x.childText("trlUPC").orElse(null));
            assertThat(s.get("upc_modifier")).as(w + " modifier").isEqualTo(x.childText("trlModifier").orElse(null));
            assertMoney(s.get("quantity"), x.requiredChildText("trlQty"), w + " quantity");
            assertMoney(s.get("line_total"), x.requiredChildText("trlLineTot"), w + " amount");
        }
    }

    private void comparePayments(long ticketId, XmlElement trans, String where) {
        List<XmlElement> payments = trans.child("trPaylines").map(p -> p.children("trPayline")).orElse(List.of());
        List<Map<String, Object>> stored = jdbc.queryForList("""
                SELECT pt.mop_code, pt.paycode, p.amount, p.payline_type FROM cpj.payment_lines p
                JOIN master.payment_type_versions v USING (payment_type_version_id) JOIN master.payment_types pt USING (payment_type_id)
                WHERE p.ticket_id = ? ORDER BY p.line_no""", ticketId);
        assertThat(stored).as(where + " payment count").hasSize(payments.size());
        for (int i = 0; i < payments.size(); i++) {
            XmlElement code = payments.get(i).requiredChild("trpPaycode");
            Map<String, Object> s = stored.get(i);
            String w = where + " payment " + (i + 1);
            assertThat(s.get("paycode")).as(w + " type").isEqualTo(code.requiredText());
            assertThat(s.get("mop_code")).as(w + " mop").isEqualTo(Integer.parseInt(code.requiredAttr("mop")));
            assertThat(s.get("payline_type")).as(w + " payline type").isEqualTo(payments.get(i).requiredAttr("type"));
            assertMoney(s.get("amount"), payments.get(i).requiredChildText("trpAmt"), w + " amount");
        }
    }

    private static void assertMoney(Object stored, String xml, String what) {
        if (xml == null) {
            assertThat(stored).as(what).isNull();
        } else {
            assertThat((BigDecimal) stored).as(what).isEqualByComparingTo(new BigDecimal(xml));
        }
    }

    /** The {@code seq}-th {@code <trans>} of the day's file, read with a plain StAX walk. */
    private static XmlElement xmlTrans(LocalDate day, int seq) throws Exception {
        XMLInputFactory factory = XmlElements.secureFactory();
        try (InputStream in = TestData.require().openCpj(day)) {
            XMLStreamReader r = factory.createXMLStreamReader(in);
            int depth = 0;
            int count = 0;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (depth == 2 && r.getLocalName().equals("trans") && ++count == seq) {
                        return XmlElements.read(r, "trans");
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                }
            }
        }
        throw new AssertionError("no trans #" + seq + " on " + day);
    }
}
