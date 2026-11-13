package com.handsoffretail.gilbarco.filetypes.pjr;

import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.fuel;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.item;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.journal;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.ring;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.tax;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.tender;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.transaction;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.handsoffretail.gilbarco.IngestionIntegrationTest;
import com.handsoffretail.gilbarco.TestBucket;
import com.handsoffretail.gilbarco.TestStores;
import com.handsoffretail.gilbarco.fetch.FetchQueryService;
import com.handsoffretail.gilbarco.fetch.FetchRequest;
import com.handsoffretail.gilbarco.verify.CheckResult;

/**
 * The PROVEN counting rules of POS_XML_FILE_TYPES.md section 3.2, end to end (zip, fetch, tables, report views)
 * on one synthetic day that holds one of each case: a sale with a promotion, a suspended prepay deposit, a voided
 * transaction, a lottery payout refund, a lottery sale, a pump sale with a fuel discount, a voided line, and the
 * cash events.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PjrCountingRulesTest extends IngestionIntegrationTest {

    private static final LocalDate DAY = LocalDate.parse("2025-01-10");

    private FetchRequest loaded;

    @BeforeAll
    void loadTheDay() {
        storeSync.sync(TestStores.ADMIN_ID);
        List<String> events = List.of(
                transaction("SaleEvent", DAY, 2, 101, false, false, "2.16", "Collected",
                        item("normal", 1, "012345678905", "COLA", "2.50", "1", "2.00", "-0.50"),
                        tax("2.00", "0.16"), tender("cash", "generic", "2.16")),
                transaction("SaleEvent", DAY, 2, 102, false, true, "20", "Collected",
                        PjrXml.prepay("normal", "20"), tender("creditCards", "generic", "20")),
                transaction("VoidEvent", DAY, 2, 103, false, false, "3.00", "Collected",
                        item("normal", 1, "012345678912", "ENERGY", "3.00", "1", "3.00", null)),
                transaction("RefundEvent", DAY, 2, 104, false, false, "-50", "Refunded",
                        ring("normal", 6, "LOTTO PAID OUT", "1", "-50"), tender("cash", "generic", "-50")),
                transaction("SaleEvent", DAY, 2, 105, false, false, "10", "Collected",
                        ring("normal", 5, "LOTTO SCRATCH OFF", "2", "10"), tender("cash", "generic", "10")),
                transaction("SaleEvent", DAY, 10001, 106, true, false, "30", "Collected",
                        fuel("003", "REGULAR", "10.000", "30.00", "-0.10"), tender("outsideCredit", "generic", "30")),
                transaction("SaleEvent", DAY, 2, 107, false, false, "1.50", "Collected",
                        item("cancel", 4, "012345678929", "CHIPS", "1.50", "1", "0", null),
                        item("normal", 4, "012345678929", "CHIPS", "1.50", "1", "1.50", null), tender("cash", "generic", "1.50")),
                PjrXml.registerOpen(DAY, 108, "150.00"),
                PjrXml.safeDrop(DAY, 109, "200"),
                PjrXml.noSale(DAY, 110));
        List<TestBucket.Entry> entries = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            entries.add(TestBucket.Entry.of(PjrXml.fileName(DAY, i + 1), journal(DAY, events.get(i))));
        }
        putPjrDay(DAY, 1, entries);
        loaded = fetch(DAY, FetchRequest.Mode.REPLACE);
    }

    @Test
    void theDayLoadsWithEveryCheckPassed() {
        assertThat(loaded.status()).as(loaded.message().orElse("")).isEqualTo(FetchRequest.Status.LOADED);
        FetchQueryService.Detail detail = fetchQueries.detail(loaded.fetchRequestId());
        assertThat(detail.rows()).containsEntry("pjr.pjr_file", 10L).containsEntry("pjr.event", 10L);
        assertThat(detail.checks()).allSatisfy(c -> assertThat(c.passed()).as(c.name() + ": " + c.detail()).isTrue());
        assertThat(detail.checks()).extracting(CheckResult::name).contains("manifest_and_sha256",
                "pjr_events_equal_manifest", "pjr_payment_gaps", "pjr_tenders_equal_grand_total");
        assertThat(count("SELECT count(*) FROM ingest.s3_object_files WHERE s3_object_id = ?", loaded.s3ObjectId().orElseThrow()))
                .isEqualTo(10);
    }

    @Test
    void suspendedSnapshotsAndVoidEventsAreNotSalesAndCustomersAreSalesPlusRefunds() {
        Map<String, Object> day = summary();
        assertThat(day.get("files")).isEqualTo(10L);
        assertThat(day.get("customers")).isEqualTo(5L);
        assertThat(day.get("pump_sales")).isEqualTo(1L);
        assertThat(day.get("prepay_deposits")).isEqualTo(1L);
        assertThat(day.get("voids")).isEqualTo(1L);
        assertThat(day.get("void_lines")).isEqualTo(1L);
        assertThat(day.get("no_sales")).isEqualTo(1L);
        money(day, "tax", "0.16");
        money(day, "payments_net", "-6.34");
        assertThat(day.get("payments_tie")).isEqualTo(true);
    }

    @Test
    void grossIsBeforePromotionsAndLotteryIsNeverNetted() {
        Map<String, Object> day = summary();
        money(day, "merchandise_net", "3.50");
        money(day, "item_sales", "3.50");
        money(day, "lottery_sales", "10");
        money(day, "lottery_payouts", "-50");
        money(day, "fuel_sales", "30.10");
        money(day, "fuel_net", "30.00");
        money(day, "total_sales_before_tax", "43.50");
        money(day, "safe_drop_amount", "200");
        money(day, "safe_loan_amount", "150");
        assertThat(one("SELECT sum(gross_sales) FROM report.rpt_items_sold_daily WHERE store_id = ? AND business_date = ?"))
                .isEqualByComparingTo("4.00");
        assertThat(one("SELECT payout_amount FROM report.rpt_lottery_daily WHERE store_id = ? AND business_date = ?"))
                .isEqualByComparingTo("50");
        assertThat(one("SELECT fuel_discount FROM report.rpt_fuel_daily WHERE store_id = ? AND business_date = ?"))
                .isEqualByComparingTo("0.10");
        assertThat(one("SELECT net_amount FROM report.rpt_payment_daily WHERE store_id = ? AND business_date = ? "
                + "AND payment_type = 'Crind CREDIT'")).isEqualByComparingTo("30");
    }

    @Test
    void aVoidedLineIsValuedAtItsRegularPrice() {
        assertThat(one("SELECT void_line_amount FROM report.rpt_void_daily WHERE store_id = ? AND business_date = ?"))
                .isEqualByComparingTo("1.50");
        assertThat(one("SELECT void_amount FROM report.rpt_void_daily WHERE store_id = ? AND business_date = ?"))
                .isEqualByComparingTo("3.00");
        assertThat(count("SELECT count(*) FROM report.v_void_line WHERE store_id = ? AND business_date = ? "
                + "AND where_found = 'prepay_deposit'", TestStores.WOLF, DAY)).isZero();
    }

    @Test
    void departmentsAreNamedByRingsAndClassedByName() {
        Map<Integer, String> classes = new java.util.TreeMap<>();
        jdbc.query("SELECT dept_number, coalesce(dept_name, '') || ':' || sales_class FROM master.departments WHERE store_id = ?",
                rs -> {
                    classes.put(rs.getInt(1), rs.getString(2));
                }, TestStores.WOLF);
        assertThat(classes).containsEntry(5, "LOTTO SCRATCH OFF:lottery_sale").containsEntry(6, "LOTTO PAID OUT:lottery_payout");
        assertThat(classes.get(4)).endsWith(":merchandise");
        assertThat(jdbc.queryForObject("SELECT master.sales_class_for('LOTTO ONLINE')", String.class)).isEqualTo("lottery_sale");
        assertThat(jdbc.queryForObject("SELECT master.sales_class_for('Lottery Payout')", String.class)).isEqualTo("lottery_payout");
        assertThat(jdbc.queryForObject("SELECT master.sales_class_for('Grocery')", String.class)).isEqualTo("merchandise");
    }

    @Test
    void refuseKeepsTheDayAndReplaceReloadsItExactlyOnce() {
        FetchRequest refused = fetch(DAY, FetchRequest.Mode.REFUSE);
        assertThat(refused.status()).isEqualTo(FetchRequest.Status.REFUSED);
        FetchRequest replaced = fetch(DAY, FetchRequest.Mode.REPLACE);
        assertThat(replaced.status()).isEqualTo(FetchRequest.Status.LOADED);
        assertThat(replaced.s3ObjectId()).isEqualTo(loaded.s3ObjectId());   // same zip: its record is reused
        assertThat(count("SELECT count(*) FROM pjr.event WHERE store_id = ? AND business_date = ?", TestStores.WOLF, DAY))
                .isEqualTo(10);
    }

    private Map<String, Object> summary() {
        return jdbc.queryForMap("SELECT * FROM report.rpt_day_summary WHERE store_id = ? AND business_date = ?",
                TestStores.WOLF, DAY);
    }

    private BigDecimal one(String sql) {
        return jdbc.queryForObject(sql, BigDecimal.class, TestStores.WOLF, DAY);
    }

    private static void money(Map<String, Object> row, String column, String expected) {
        assertThat((BigDecimal) row.get(column)).as(column).isEqualByComparingTo(expected);
    }
}
