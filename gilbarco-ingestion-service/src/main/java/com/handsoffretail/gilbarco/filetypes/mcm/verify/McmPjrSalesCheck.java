package com.handsoffretail.gilbarco.filetypes.mcm.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.handsoffretail.gilbarco.verify.CheckContext;
import com.handsoffretail.gilbarco.verify.CheckResult;

/**
 * Soft: when the day's PJR is already loaded, MCM's department sales equal the PJR rebuild (report.rpt_mcm_day_check)
 * and no department differs. A difference is recorded, never refused: MCM is still right about the names, and the
 * cause is on the PJR side (a missing file or a broken counting rule). The view stays the live answer, also for
 * PJR loaded later.
 */
@Component
@Order(220)
class McmPjrSalesCheck implements McmLoadCheck {

    private final JdbcTemplate jdbc;

    McmPjrSalesCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CheckResult> run(CheckContext c) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT pjr_loaded, mcm_sales, pjr_sales, departments, departments_off
                FROM report.rpt_mcm_day_check WHERE store_id = ? AND business_date = ?""",
                c.store().storeId(), c.businessDate());
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("pjr_loaded"))) {
            return List.of(new CheckResult("mcm_sales_equal_pjr", Optional.empty(), Optional.empty(), true, false,
                    "skipped: PJR of " + c.businessDate() + " not loaded yet; see report.rpt_mcm_day_check"));
        }
        Map<String, Object> r = rows.get(0);
        long departments = ((Number) r.get("departments")).longValue();
        long off = ((Number) r.get("departments_off")).longValue();
        return List.of(
                CheckResult.amounts("mcm_sales_equal_pjr", (BigDecimal) r.get("mcm_sales"), (BigDecimal) r.get("pjr_sales"),
                        false, "MCM SalesAmount vs PJR gross + refund, all departments"),
                CheckResult.counted("mcm_departments_equal_pjr", 0, off, false,
                        off + " of " + departments + " departments differ (sales, promotions or refunds); "
                                + "see report.rpt_mcm_department_check"));
    }
}
