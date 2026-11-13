package com.handsoffretail.gilbarco.filetypes.pjr.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.handsoffretail.gilbarco.verify.CheckContext;
import com.handsoffretail.gilbarco.verify.CheckResult;

/**
 * Soft: for the day's counted transactions, the tenders (status normal) add up to the transactions' grand totals.
 * Passport writes a Refunded grand total already negative (Wolf 2026-10-15: 14 refunds, -1,037.00 grand = -1,037.00
 * tendered), so the amounts are summed as written.
 */
@Component
@Order(130)
class PjrTenderBalanceCheck implements PjrLoadCheck {

    private final JdbcTemplate jdbc;

    PjrTenderBalanceCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CheckResult> run(CheckContext c) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT coalesce(sum(e.total_grand), 0) AS grand,
                       coalesce(sum(t.paid), 0) AS paid
                FROM pjr.event e
                LEFT JOIN LATERAL (SELECT sum(amount) AS paid FROM pjr.tender_line
                                   WHERE file_id = e.file_id AND status = 'normal') t ON true
                WHERE e.store_id = ? AND e.business_date = ? AND e.counts_as_sale
                """, c.store().storeId(), c.businessDate());
        return List.of(CheckResult.amounts("pjr_tenders_equal_grand_total", (BigDecimal) row.get("grand"),
                (BigDecimal) row.get("paid"), false, "sum of TransactionTotalGrandAmount vs sum of tenders, counted transactions"));
    }
}
