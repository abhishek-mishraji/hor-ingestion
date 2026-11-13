package com.handsoffretail.gilbarco.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.handsoffretail.gilbarco.IngestionIntegrationTest;
import com.handsoffretail.gilbarco.TestData;
import com.handsoffretail.gilbarco.TestStores;
import com.handsoffretail.gilbarco.fetch.FetchQueryService;
import com.handsoffretail.gilbarco.fetch.FetchRequest;

/**
 * Fetches every real Wolf PJR business day of 22-30 Sep 2026 (6,618 files) through the real flow (bucket layout,
 * manifest, parser, loader, checks) and checks the six full days against the printed StoreClose PDFs, to the cent.
 * Figures and assertions ported from HOR-XML/pjr-platform GoldenDayTest (Wolf/loader/reconcile.py, PDF statistics).
 * Skipped when the test data is absent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PjrGoldenDayTest extends IngestionIntegrationTest {

    private static final long WOLF = TestStores.WOLF;

    private final Map<LocalDate, FetchRequest> fetched = new LinkedHashMap<>();
    private TestData data;

    @BeforeAll
    void fetchEveryRealDay() {
        data = TestData.require();
        storeSync.sync(TestStores.ADMIN_ID);
        Map<LocalDate, Long> started = new LinkedHashMap<>();
        for (LocalDate day : data.pjrByBusinessDate().keySet()) {
            started.put(day, fetches.start(WOLF, day, FetchRequest.Mode.REPLACE, PJR, TestStores.ADMIN_ID));
        }
        started.forEach((day, id) -> fetched.put(day, awaitEnd(id)));
    }

    // ------------------------------------------------------------------ expected figures (StoreClose PDFs)

    record Day(String date, int customers, int pumpSales, String tax, String nonFuelGross, String fuelDiscounts,
            String[] gradeDollars, String[] gradeGallons, Map<String, String> tenders,
            int drops, String dropAmount, int noSales, int voids, String voidAmount, int payouts, String payoutAmount,
            String lottoOnline, String paidOut, int paidOutCount, String scratch, int scratchCount) {

        @Override
        public String toString() {
            return date;
        }
    }

    static Map<String, String> tenders(String cash, String credit, String debit, String ebt, String house,
            String crindCredit, String crindDebit) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("cash/generic", cash);
        m.put("creditCards/generic", credit);
        m.put("debitCards/generic", debit);
        m.put("generic/generic", ebt);
        m.put("generic/store", house);
        m.put("outsideCredit/generic", crindCredit);
        m.put("outsideDebit/generic", crindDebit);
        return m;
    }

    static List<Day> days() {
        List<Day> d = new ArrayList<>();
        d.add(new Day("2026-09-23", 672, 78, "160.73", "3295.59", "-0.34",
                new String[] {"234.55", "526.60", "6103.71"}, new String[] {"42.41", "102.56", "1373.93"},
                tenders("4307.49", "2372.37", "1085.54", "48.74", "0", "1515.42", "911.93"),
                3, "3921", 11, 2, "0", 0, "0", "871.50", "-1152.00", 28, "721.00", 172));
        d.add(new Day("2026-09-24", 709, 76, "190.06", "3463.62", "-7.87",
                new String[] {"324.02", "606.15", "6927.30"}, new String[] {"58.42", "118.35", "1557.34"},
                tenders("4191.09", "2710.66", "1855.20", "59.46", "0", "1400.71", "1198.21"),
                3, "3806", 20, 2, "10.49", 2, "-368.00", "844.00", "-1124.00", 29, "452.00", 180));
        d.add(new Day("2026-09-25", 787, 76, "238.42", "4610.94", "-2.96",
                new String[] {"574.22", "645.26", "6464.43"}, new String[] {"102.92", "126.10", "1455.86"},
                tenders("5273.83", "2595.05", "2087.20", "17.74", "0", "1525.00", "928.76"),
                2, "4819", 20, 1, "10.48", 2, "-284.20", "854.00", "-1062.00", 29, "1021.00", 251));
        d.add(new Day("2026-09-26", 643, 58, "203.68", "3703.46", "0",
                new String[] {"386.63", "382.91", "5812.25"}, new String[] {"69.83", "75.12", "1309.50"},
                tenders("4694.91", "2073.67", "1594.36", "6.08", "117.90", "898.44", "1028.72"),
                3, "4437", 14, 3, "21.33", 0, "0", "643.50", "-893.00", 20, "748.00", 113));
        d.add(new Day("2026-09-27", 591, 42, "141.76", "3050.32", "0",
                new String[] {"575.62", "450.25", "5006.95"}, new String[] {"103.48", "87.58", "1127.93"},
                tenders("3940.87", "2246.46", "1425.57", "1.99", "0", "848.39", "695.67"),
                2, "3765", 19, 0, "0", 0, "0", "481.00", "-1021.00", 40, "1194.00", 217));
        d.add(new Day("2026-09-28", 682, 83, "202.69", "4154.58", "0",
                new String[] {"485.26", "267.02", "6115.93"}, new String[] {"87.37", "52.20", "1375.18"},
                tenders("4866.85", "2382.52", "1293.88", "0.79", "0", "1416.71", "1180.60"),
                3, "4804", 17, 2, "0", 0, "0", "819.50", "-547.00", 22, "807.00", 191));
        return d;
    }

    // ------------------------------------------------------------------ helpers

    private BigDecimal one(String sql, Object... args) {
        BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, args);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static void money(String label, String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), label + ": expected " + expected + ", got " + actual);
    }

    private static final String COUNTED = "FROM pjr.event e WHERE e.store_id = " + WOLF
            + " AND e.business_date = ?::date AND e.counts_as_sale";
    private static final String COUNTED_LINES = "FROM pjr.event_line l JOIN pjr.event e USING (file_id) WHERE e.store_id = "
            + WOLF + " AND e.business_date = ?::date AND e.counts_as_sale AND l.status = 'normal'";

    // ------------------------------------------------------------------ tests

    @Test
    void everyDayLoadsEveryFileWithEveryCheckPassed() {
        assertThat(fetched).isNotEmpty();
        long events = 0;
        for (Map.Entry<LocalDate, FetchRequest> e : fetched.entrySet()) {
            FetchRequest r = e.getValue();
            assertThat(r.status()).as(e.getKey() + ": " + r.message().orElse("")).isEqualTo(FetchRequest.Status.LOADED);
            FetchQueryService.Detail detail = fetchQueries.detail(r.fetchRequestId());
            assertThat(detail.checks()).allSatisfy(c -> assertThat(c.passed()).as(e.getKey() + " " + c.name() + " expected "
                    + c.expected().orElse(null) + " actual " + c.actual().orElse(null)).isTrue());
            assertThat(detail.rows().get("pjr.event")).isEqualTo(data.pjrByBusinessDate().get(e.getKey()).size());
            events += detail.rows().get("pjr.event");
        }
        assertThat(events).isEqualTo(data.files()).isEqualTo(6618);
        assertThat(count("SELECT count(*) FROM pjr.event WHERE store_id = ? AND event_type = 'SaleEvent' AND is_suspended"
                + " AND NOT EXISTS (SELECT 1 FROM pjr.event_line l WHERE l.file_id = pjr.event.file_id"
                + " AND l.line_type = 'FuelPrepayLine' AND l.status = 'normal')", WOLF))
                .as("every suspended sale is a prepay deposit").isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("days")
    void paymentsTieOnEveryCountedTransaction(Day d) {
        assertThat(count("SELECT count(*) FROM report.rpt_payment_gap WHERE store_id = ? AND business_date = ?::date",
                WOLF, d.date())).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("days")
    void salesTaxAndCustomers(Day d) {
        assertEquals(d.customers(), one("SELECT count(*) " + COUNTED, d.date()).intValue(), "customers");
        assertEquals(d.pumpSales(), one("SELECT count(*) " + COUNTED + " AND e.event_type = 'SaleEvent' AND e.is_outside",
                d.date()).intValue(), "CRIND sales");
        money("tax", d.tax(), one("SELECT sum(t.tax_collected) FROM pjr.event_tax t JOIN pjr.event e USING (file_id)"
                + " WHERE e.store_id = ? AND e.business_date = ?::date AND e.counts_as_sale AND t.status = 'normal'",
                WOLF, d.date()));
        money("non-fuel gross", d.nonFuelGross(), one("SELECT sum(l.gross_amount) " + COUNTED_LINES
                + " AND l.line_type IN ('ItemLine', 'MerchandiseCodeLine')", d.date()));
        Map<String, Object> summary = jdbc.queryForMap(
                "SELECT customers, pump_sales, tax FROM report.rpt_day_summary WHERE store_id = ? AND business_date = ?::date",
                WOLF, d.date());
        assertThat(((Number) summary.get("customers")).intValue()).isEqualTo(d.customers());
        assertThat(((Number) summary.get("pump_sales")).intValue()).isEqualTo(d.pumpSales());
        money("summary tax", d.tax(), (BigDecimal) summary.get("tax"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("days")
    void fuelByGrade(Day d) {
        String[] grades = {"001", "002", "003"};
        for (int i = 0; i < grades.length; i++) {
            money("grade " + grades[i] + " $", d.gradeDollars()[i], one("SELECT sum(l.gross_amount) " + COUNTED_LINES
                    + " AND l.line_type = 'FuelLine' AND l.fuel_grade_id = ?", d.date(), grades[i]));
            money("grade " + grades[i] + " gal", d.gradeGallons()[i], one("SELECT round(sum(l.quantity), 2) " + COUNTED_LINES
                    + " AND l.line_type = 'FuelLine' AND l.fuel_grade_id = ?", d.date(), grades[i]));
        }
        money("fuel discounts", d.fuelDiscounts(), one("SELECT sum(l.promotion_amount) " + COUNTED_LINES
                + " AND l.line_type = 'FuelLine'", d.date()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("days")
    void paymentsByType(Day d) {
        for (Map.Entry<String, String> t : d.tenders().entrySet()) {
            money(t.getKey(), t.getValue(), one("SELECT sum(t.amount) FROM pjr.tender_line t JOIN pjr.event e USING (file_id)"
                    + " WHERE e.store_id = ? AND e.business_date = ?::date AND e.counts_as_sale AND t.status = 'normal'"
                    + " AND t.tender_code || '/' || coalesce(t.tender_sub, '') = ?", WOLF, d.date(), t.getKey()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("days")
    void cashAndSuspicious(Day d) {
        String kind = "SELECT count(*) FROM pjr.event WHERE store_id = ? AND business_date = ?::date AND detail_kind = ?";
        String amount = "SELECT sum(detail_amount) FROM pjr.event WHERE store_id = ? AND business_date = ?::date AND detail_kind = ?";
        assertEquals(d.drops(), one(kind, WOLF, d.date(), "safe_drop").intValue(), "safe drops");
        money("safe drop amount", d.dropAmount(), one(amount, WOLF, d.date(), "safe_drop"));
        assertEquals(4, one(kind, WOLF, d.date(), "register_open").intValue(), "safe loans");
        money("safe loan amount", "600", one(amount, WOLF, d.date(), "register_open"));
        assertEquals(d.payouts(), one(kind, WOLF, d.date(), "payout").intValue(), "paid outs");
        money("paid out amount", d.payoutAmount(), one(amount, WOLF, d.date(), "payout"));
        assertEquals(d.noSales(), one(kind, WOLF, d.date(), "no_sale").intValue(), "no sales");
        assertEquals(d.voids(), one(kind, WOLF, d.date(), "void").intValue(), "transaction voids");
        money("transaction void amount", d.voidAmount(), one("SELECT sum(total_grand) FROM pjr.event WHERE store_id = ?"
                + " AND business_date = ?::date AND detail_kind = 'void'", WOLF, d.date()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("days")
    void lotteryDepartments(Day d) {
        String gross = "SELECT sum(l.gross_amount) " + COUNTED_LINES + " AND l.dept_number = ?";
        String qty = "SELECT sum(l.quantity) " + COUNTED_LINES + " AND l.dept_number = ?";
        money("LOTTO ONLINE", d.lottoOnline(), one(gross, d.date(), 57));
        money("LOTTO PAID OUT", d.paidOut(), one(gross, d.date(), 6));
        assertEquals(d.paidOutCount(), one(qty, d.date(), 6).intValue(), "LOTTO PAID OUT count");
        money("LOTTO SCRATCH OFF", d.scratch(), one(gross, d.date(), 5));
        assertEquals(d.scratchCount(), one(qty, d.date(), 5).intValue(), "LOTTO SCRATCH OFF count");
        money("report lottery sales", new BigDecimal(d.lottoOnline()).add(new BigDecimal(d.scratch())).toPlainString(),
                one("SELECT lottery_sales FROM report.rpt_day_summary WHERE store_id = ? AND business_date = ?::date",
                        WOLF, d.date()));
    }

    @Test
    void departmentNamesComeOnlyFromRingsAndLotteryIsClassedByName() {
        Map<Integer, String> names = new LinkedHashMap<>();
        Map<Integer, String> classes = new LinkedHashMap<>();
        jdbc.query("SELECT dept_number, dept_name, sales_class FROM master.departments WHERE store_id = ?", rs -> {
            names.put(rs.getInt(1), rs.getString(2));
            classes.put(rs.getInt(1), rs.getString(3));
        }, WOLF);
        assertThat(names).containsEntry(5, "LOTTO SCRATCH OFF").containsEntry(6, "LOTTO PAID OUT").containsEntry(1, "Grocery");
        assertThat(names).containsKeys(4, 13, 14, 21, 51);
        assertThat(classes).containsEntry(5, "lottery_sale").containsEntry(57, "lottery_sale").containsEntry(6, "lottery_payout");
        classes.forEach((k, v) -> {
            if (!List.of(5, 6, 57).contains(k)) {
                assertThat(v).as("department " + k + " " + names.get(k)).isEqualTo("merchandise");
            }
        });
    }
}
