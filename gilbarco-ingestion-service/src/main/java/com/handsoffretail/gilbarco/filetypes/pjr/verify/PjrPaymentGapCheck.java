package com.handsoffretail.gilbarco.filetypes.pjr.verify;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.handsoffretail.gilbarco.verify.CheckContext;
import com.handsoffretail.gilbarco.verify.CheckResult;

/**
 * Soft: on every counted transaction, payments = sold lines + tax (report.rpt_payment_gap is empty for the day).
 * Equal on all 4,910 transactions of 22-30 Sep 2026; a gap is a transaction the proven rules do not explain.
 */
@Component
@Order(120)
class PjrPaymentGapCheck implements PjrLoadCheck {

    private final JdbcTemplate jdbc;

    PjrPaymentGapCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CheckResult> run(CheckContext c) {
        Long gaps = jdbc.queryForObject("SELECT count(*) FROM report.rpt_payment_gap WHERE store_id = ? AND business_date = ?",
                Long.class, c.store().storeId(), c.businessDate());
        return List.of(CheckResult.counted("pjr_payment_gaps", 0, gaps == null ? 0 : gaps, false,
                "counted transactions whose payments differ from lines + tax (report.rpt_payment_gap)"));
    }
}
