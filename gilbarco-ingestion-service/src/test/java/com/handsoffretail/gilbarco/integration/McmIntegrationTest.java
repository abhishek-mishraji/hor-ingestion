package com.handsoffretail.gilbarco.integration;

import static com.handsoffretail.gilbarco.filetypes.mcm.McmXml.detail;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.item;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.journal;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.ring;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.tender;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.transaction;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.handsoffretail.gilbarco.IngestionIntegrationTest;
import com.handsoffretail.gilbarco.TestBucket;
import com.handsoffretail.gilbarco.TestStores;
import com.handsoffretail.gilbarco.fetch.FetchRequest;
import com.handsoffretail.gilbarco.filetypes.mcm.McmXml;
import com.handsoffretail.gilbarco.filetypes.pjr.PjrXml;

/**
 * MCM through the real fetch flow: the store-level file is stored (the per-register file is not), it names a
 * department PJR only scans, an admin's name is kept, and report.rpt_mcm_day_check proves the PJR day complete
 * or shows what is missing. Uses its own departments (92, 93, 94) and days.
 */
class McmIntegrationTest extends IngestionIntegrationTest {

    private static final String MCM = "MCM";
    private static final int RUNG = 92;
    private static final int SCANNED = 93;
    private static final int ADMIN_NAMED = 94;

    @Test
    void theStoreFileNamesDepartmentsAndProvesThePjrDayComplete() {
        LocalDate day = LocalDate.parse("2025-03-10");
        putPjrDay(day, 1, List.of(rungSale(day, 1), scannedSale(day, 2, SCANNED, "3.00"), scannedSale(day, 3, ADMIN_NAMED, "2.00")));
        assertThat(fetch(day, FetchRequest.Mode.REPLACE).status()).isEqualTo(FetchRequest.Status.LOADED);
        assertThat(deptName(SCANNED)).isNull();
        jdbc.update("""
                UPDATE master.departments SET dept_name = 'ADMIN NAME', admin_set_by = ?, admin_set_at = now()
                WHERE store_id = ? AND dept_number = ?""", TestStores.ADMIN_ID, TestStores.WOLF, ADMIN_NAMED);

        putMcmDay(day, 1, McmXml.store(day, detail(RUNG, "RUNG DEPT", "5", "1"), detail(SCANNED, "SCANNED DEPT", "3", "1"),
                detail(ADMIN_NAMED, "POS NAME", "2", "1")),
                McmXml.registers(day, detail(RUNG, "RUNG DEPT", "5", "1")));
        FetchRequest mcm = fetchMcm(day, FetchRequest.Mode.REPLACE);
        assertThat(mcm.status()).as(mcm.message().orElse("")).isEqualTo(FetchRequest.Status.LOADED);

        assertThat(count("SELECT count(*) FROM mcm.department_day WHERE store_id = ? AND business_date = ?",
                TestStores.WOLF, day)).isEqualTo(3);
        assertThat(deptName(SCANNED)).isEqualTo("SCANNED DEPT");
        assertThat(deptName(ADMIN_NAMED)).isEqualTo("ADMIN NAME");
        assertThat(jdbc.queryForObject("SELECT pos_name FROM master.departments WHERE store_id = ? AND dept_number = ?",
                String.class, TestStores.WOLF, ADMIN_NAMED)).isEqualTo("POS NAME");

        Map<String, Object> check = dayCheck(day);
        assertThat(check.get("status")).isEqualTo("match");
        assertThat((BigDecimal) check.get("mcm_sales")).isEqualByComparingTo("10");
        assertThat(checkPassed(mcm.fetchRequestId(), "mcm_sales_equal_pjr")).isTrue();
        assertThat(checkPassed(mcm.fetchRequestId(), "mcm_departments_stored")).isTrue();

        // Fetched again with REFUSE: nothing changes.
        assertThat(fetchMcm(day, FetchRequest.Mode.REFUSE).status()).isEqualTo(FetchRequest.Status.REFUSED);
    }

    @Test
    void aDifferenceIsShownAndRecordedButTheMcmDayStaysLoaded() {
        LocalDate day = LocalDate.parse("2025-03-11");
        putPjrDay(day, 1, List.of(rungSale(day, 1)));
        assertThat(fetch(day, FetchRequest.Mode.REPLACE).status()).isEqualTo(FetchRequest.Status.LOADED);

        // Passport counted a sale of department 93 that no loaded PJR file shows (a missing file).
        putMcmDay(day, 1, McmXml.store(day, detail(RUNG, "RUNG DEPT", "5", "1"), detail(SCANNED, "SCANNED DEPT", "4", "1")),
                McmXml.registers(day));
        FetchRequest mcm = fetchMcm(day, FetchRequest.Mode.REPLACE);
        assertThat(mcm.status()).isEqualTo(FetchRequest.Status.LOADED);

        Map<String, Object> check = dayCheck(day);
        assertThat(check.get("status")).isEqualTo("mismatch");
        assertThat(((Number) check.get("departments_off")).longValue()).isEqualTo(1);
        assertThat((BigDecimal) check.get("sales_diff")).isEqualByComparingTo("-4");
        assertThat(checkPassed(mcm.fetchRequestId(), "mcm_departments_equal_pjr")).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT matches FROM report.rpt_mcm_department_check WHERE store_id = ? AND business_date = ? AND dept_number = ?""",
                Boolean.class, TestStores.WOLF, day, SCANNED)).isFalse();
    }

    @Test
    void aMissingSideIsNamedAndAPackageWithoutTheStoreFileFails() {
        LocalDate pjrOnly = LocalDate.parse("2025-03-12");
        putPjrDay(pjrOnly, 1, List.of(rungSale(pjrOnly, 1)));
        assertThat(fetch(pjrOnly, FetchRequest.Mode.REPLACE).status()).isEqualTo(FetchRequest.Status.LOADED);
        assertThat(dayCheck(pjrOnly).get("status")).isEqualTo("mcm_missing");

        LocalDate mcmOnly = LocalDate.parse("2025-03-13");
        putMcmDay(mcmOnly, 1, McmXml.store(mcmOnly, detail(RUNG, "RUNG DEPT", "5", "1")), McmXml.registers(mcmOnly));
        FetchRequest loaded = fetchMcm(mcmOnly, FetchRequest.Mode.REPLACE);
        assertThat(loaded.status()).isEqualTo(FetchRequest.Status.LOADED);
        assertThat(dayCheck(mcmOnly).get("status")).isEqualTo("pjr_missing");
        assertThat(checkPassed(loaded.fetchRequestId(), "mcm_sales_equal_pjr")).isTrue();   // skipped, not failed

        LocalDate broken = LocalDate.parse("2025-03-14");
        putMcmDay(broken, 1, McmXml.registers(broken, detail(RUNG, "RUNG DEPT", "5", "1")), McmXml.registers(broken));
        FetchRequest failed = fetchMcm(broken, FetchRequest.Mode.REPLACE);
        assertThat(failed.status()).isEqualTo(FetchRequest.Status.FAILED);
        assertThat(failed.message().orElseThrow()).contains("0 store-level (PrimaryReportPeriod 2) files");
        assertThat(count("SELECT count(*) FROM mcm.department_day WHERE store_id = ? AND business_date = ?",
                TestStores.WOLF, broken)).isZero();
    }

    private FetchRequest fetchMcm(LocalDate day, FetchRequest.Mode mode) {
        return awaitEnd(fetches.start(TestStores.WOLF, day, mode, MCM, TestStores.ADMIN_ID));
    }

    /** The two files Passport writes at the close: store level and per register. */
    private static void putMcmDay(LocalDate day, int version, String storeFile, String registerFile) {
        try {
            bucket().put(day, version, MCM, List.of(TestBucket.Entry.of(McmXml.fileName(day, 6), registerFile),
                    TestBucket.Entry.of(McmXml.fileName(day, 7), storeFile)), TestBucket.Corruption.NONE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Object> dayCheck(LocalDate day) {
        return jdbc.queryForMap("SELECT * FROM report.rpt_mcm_day_check WHERE store_id = ? AND business_date = ?",
                TestStores.WOLF, day);
    }

    private boolean checkPassed(long fetchId, String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT passed FROM ingest.load_checks WHERE fetch_request_id = ? AND check_name = ?", Boolean.class, fetchId, name));
    }

    private String deptName(int dept) {
        return jdbc.queryForObject("SELECT dept_name FROM master.departments WHERE store_id = ? AND dept_number = ?",
                String.class, TestStores.WOLF, dept);
    }

    private static TestBucket.Entry rungSale(LocalDate day, int n) {
        return TestBucket.Entry.of(PjrXml.fileName(day, n), journal(day, transaction("SaleEvent", day, 2, n, false, false,
                "5", "Collected", ring("normal", RUNG, "RUNG DEPT", "1", "5"), tender("cash", "generic", "5"))));
    }

    private static TestBucket.Entry scannedSale(LocalDate day, int n, int dept, String amount) {
        return TestBucket.Entry.of(PjrXml.fileName(day, n), journal(day, transaction("SaleEvent", day, 2, n, false, false,
                amount, "Collected", item("normal", dept, "0001234567" + n, "ITEM " + n, amount, "1", amount, null),
                tender("cash", "generic", amount))));
    }
}
