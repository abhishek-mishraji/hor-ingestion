package com.handsoffretail.gilbarco.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.handsoffretail.gilbarco.IngestionIntegrationTest;
import com.handsoffretail.gilbarco.TestEnv;
import com.handsoffretail.gilbarco.TestStores;
import com.handsoffretail.gilbarco.fetch.FetchQueryService;
import com.handsoffretail.gilbarco.fetch.FetchRequest;
import com.handsoffretail.gilbarco.verify.CheckResult;

/**
 * The real thing end to end: the application with {@code hor.storage.mode=s3} fetches real Wolf PJR day packages
 * from the hor-stores-data bucket (read only) into gilbarco_db_test. Skipped without the AWS key.
 * 2026-10-10 holds a pay-in (FinancialEvent / PayInDetail); 2026-10-15 is a full day.
 */
@TestPropertySource(properties = "hor.storage.mode=s3")
class RealS3FetchTest extends IngestionIntegrationTest {

    @BeforeAll
    static void needsTheKey() {
        Assumptions.assumeTrue(TestEnv.get("HOR_AWS_ACCESS_KEY_ID").isPresent()
                && TestEnv.get("HOR_AWS_SECRET_ACCESS_KEY").isPresent(), "no AWS key in the secrets file: real-S3 fetch skipped");
    }

    @Test
    void aFullRealDayIsFetchedFromS3AndLoaded() {
        LocalDate day = LocalDate.parse("2026-10-15");
        FetchRequest fetched = fetch(day, FetchRequest.Mode.REPLACE);

        assertThat(fetched.status()).as(fetched.message().orElse("")).isEqualTo(FetchRequest.Status.LOADED);
        FetchQueryService.Detail detail = fetchQueries.detail(fetched.fetchRequestId());
        assertThat(detail.zip()).hasValueSatisfying(z -> {
            assertThat(z.bucket()).isEqualTo("hor-stores-data");
            assertThat(z.key()).isEqualTo("wolf_800_NY-13208/wolf_800_NY-13208/2026/october/2026-10-15/PJR/"
                    + "wolf_800_NY-13208_PJR_2026-10-15_v1.zip");
            assertThat(detail.rows().get("pjr.event")).isEqualTo(z.files()).isGreaterThan(500);
        });
        assertThat(detail.checks()).allSatisfy(c -> assertThat(c.passed())
                .as(c.name() + " expected " + c.expected().orElse(null) + " actual " + c.actual().orElse(null)).isTrue());
        assertThat(detail.checks()).extracting(CheckResult::name).contains("pjr_payment_gaps", "pjr_tenders_equal_grand_total");

        Map<String, Object> summary = jdbc.queryForMap("SELECT * FROM report.rpt_day_summary WHERE store_id = ? AND business_date = ?",
                TestStores.WOLF, day);
        System.out.println("REAL S3 2026-10-15 day summary " + summary);
        assertThat(((Number) summary.get("customers")).longValue()).isPositive();
    }

    /** 2026-10-24: MCM names every department and proves the PJR day complete (StoreClose "Sales" $10,587.90). */
    @Test
    void aRealMcmDayNamesTheDepartmentsAndMatchesItsPjrDay() {
        LocalDate day = LocalDate.parse("2026-10-24");
        assertThat(fetch(day, FetchRequest.Mode.REPLACE).status()).isEqualTo(FetchRequest.Status.LOADED);
        FetchRequest mcm = awaitEnd(fetches.start(TestStores.WOLF, day, FetchRequest.Mode.REPLACE, "MCM", TestStores.ADMIN_ID));

        assertThat(mcm.status()).as(mcm.message().orElse("")).isEqualTo(FetchRequest.Status.LOADED);
        assertThat(fetchQueries.detail(mcm.fetchRequestId()).checks()).allSatisfy(c -> assertThat(c.passed())
                .as(c.name() + " expected " + c.expected().orElse(null) + " actual " + c.actual().orElse(null)).isTrue());
        Map<String, Object> check = jdbc.queryForMap("SELECT * FROM report.rpt_mcm_day_check WHERE store_id = ? AND business_date = ?",
                TestStores.WOLF, day);
        assertThat(check.get("status")).as(check.toString()).isEqualTo("match");
        assertThat((BigDecimal) check.get("mcm_sales")).isEqualByComparingTo("10587.90");
        assertThat(count("SELECT count(*) FROM mcm.department_day WHERE store_id = ? AND business_date = ?", TestStores.WOLF, day))
                .isEqualTo(22);
        // Scanned-only on 10-24 (no department ring names them in PJR); MCM does.
        assertThat(jdbc.queryForObject("SELECT pos_name FROM master.departments WHERE store_id = ? AND dept_number = 46",
                String.class, TestStores.WOLF)).isEqualTo("Energy Drinks");
    }

    @Test
    void aRealDayWithAPayInLoads() {
        LocalDate day = LocalDate.parse("2026-10-10");
        FetchRequest fetched = fetch(day, FetchRequest.Mode.REPLACE);

        assertThat(fetched.status()).as(fetched.message().orElse("")).isEqualTo(FetchRequest.Status.LOADED);
        assertThat(count("SELECT count(*) FROM pjr.event WHERE store_id = ? AND business_date = ? AND detail_kind = 'pay_in'",
                TestStores.WOLF, day)).isPositive();
        assertThat(count("SELECT count(*) FROM report.rpt_cash_movement WHERE store_id = ? AND business_date = ? AND kind = 'pay_in'",
                TestStores.WOLF, day)).isPositive();
        assertThat(count("SELECT count(*) FROM pjr.event WHERE detail_kind = 'pay_in' AND payee IS NOT NULL")).isZero();
    }
}
