package com.handsoffretail.verifone.verify;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLoadCheckRepository implements LoadCheckRepository {

    private final JdbcTemplate jdbc;

    JdbcLoadCheckRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(long cpjFileId, List<CheckResult> results) {
        jdbc.batchUpdate("""
                INSERT INTO cpj.load_checks (cpj_file_id, check_name, expected, actual, passed, detail)
                VALUES (?, ?, ?, ?, ?, ?)
                """, results.stream().map(r -> new Object[] {cpjFileId, r.name(), r.expected().orElse(null),
                        r.actual().orElse(null), r.passed(), (r.hard() ? "hard" : "soft") + (r.detail().isBlank() ? "" : ": " + r.detail())})
                .toList(),
                new int[] {Types.BIGINT, Types.VARCHAR, Types.NUMERIC, Types.NUMERIC,
                        Types.BOOLEAN, Types.VARCHAR});
    }

    @Override
    public Map<String, Long> storedCounts(long f) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT trans_type, count(*) FROM cpj.tickets WHERE cpj_file_id = ? GROUP BY trans_type",
                rs -> {
                    counts.put("trans[" + rs.getString(1) + "]", rs.getLong(2));
                }, f);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM cpj.cashier_sessions WHERE cpj_file_id = ? AND begin_cash IS NOT NULL) AS open_cashier,
                       (SELECT count(*) FROM cpj.cashier_sessions WHERE cpj_file_id = ? AND closed_at IS NOT NULL) AS close_cashier,
                       (SELECT count(*) FROM cpj.period_closes WHERE cpj_file_id = ?) AS period_close,
                       (SELECT count(*) FROM cpj.ticket_lines l JOIN cpj.tickets t ON t.ticket_id = l.ticket_id WHERE t.cpj_file_id = ?) AS ticket_lines,
                       (SELECT count(*) FROM cpj.ticket_lines l JOIN cpj.cashier_events e ON e.cashier_event_id = l.cashier_event_id WHERE e.cpj_file_id = ?) AS event_lines,
                       (SELECT count(*) FROM cpj.payment_lines p JOIN cpj.tickets t ON t.ticket_id = p.ticket_id WHERE t.cpj_file_id = ?) AS paylines,
                       (SELECT count(*) FROM cpj.payment_card_details c JOIN cpj.payment_lines p ON p.payment_line_id = c.payment_line_id
                                         JOIN cpj.tickets t ON t.ticket_id = p.ticket_id WHERE t.cpj_file_id = ?) AS card_infos,
                       (SELECT count(*) FROM cpj.ticket_age_checks a JOIN cpj.tickets t ON t.ticket_id = a.ticket_id WHERE t.cpj_file_id = ?) AS age_checks,
                       (SELECT count(*) FROM cpj.cashier_events WHERE cpj_file_id = ?) AS events
                """, f, f, f, f, f, f, f, f, f);
        counts.put("trans[openCashier]", ((Number) row.get("open_cashier")).longValue());
        counts.put("trans[closeCashier]", ((Number) row.get("close_cashier")).longValue());
        counts.put("trans[periodClose]", ((Number) row.get("period_close")).longValue());
        counts.put(CountReconciler.TICKET_LINES, ((Number) row.get("ticket_lines")).longValue());
        counts.put(CountReconciler.EVENT_LINES, ((Number) row.get("event_lines")).longValue());
        counts.put(CountReconciler.PAYLINES, ((Number) row.get("paylines")).longValue());
        counts.put(CountReconciler.CARD_INFOS, ((Number) row.get("card_infos")).longValue());
        counts.put(CountReconciler.AGE_CHECKS, ((Number) row.get("age_checks")).longValue());
        counts.put(CountReconciler.KEPT_EVENTS, ((Number) row.get("events")).longValue());
        return counts;
    }

    @Override
    public BigDecimal counterChange(long f) {
        return jdbc.query("""
                SELECT e.overall_sales - s.overall_sales FROM cpj.file_totals s
                JOIN cpj.file_totals e ON e.cpj_file_id = s.cpj_file_id AND e.totals_kind = 'END'
                WHERE s.cpj_file_id = ? AND s.totals_kind = 'START'
                """, rs -> rs.next() ? rs.getBigDecimal(1) : null, f);
    }

    @Override
    public BigDecimal countedSalesTotal(long f) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(t.total), 0) FROM cpj.tickets t
                WHERE t.cpj_file_id = ? AND t.trans_type IN ('sale', 'network sale', 'refund sale')
                  AND NOT t.is_suspended AND NOT t.is_fuel_prepay AND NOT t.is_rollback
                """, BigDecimal.class, f);
    }

    @Override
    public BigDecimal storedCouponSum(long f) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(c.discount_amount), 0) FROM cpj.ticket_coupons c
                JOIN cpj.tickets t ON t.ticket_id = c.ticket_id WHERE t.cpj_file_id = ?
                """, BigDecimal.class, f);
    }

    @Override
    public long foreignCodeReferences(long f, long store) {
        Long count = jdbc.queryForObject("""
                WITH t AS (SELECT ticket_id, register_id, house_account_version_id FROM cpj.tickets WHERE cpj_file_id = :f),
                     e AS (SELECT cashier_event_id, register_id, cashier_version_id FROM cpj.cashier_events WHERE cpj_file_id = :f),
                     l AS (SELECT l.* FROM cpj.ticket_lines l WHERE l.ticket_id IN (SELECT ticket_id FROM t)
                                                               OR l.cashier_event_id IN (SELECT cashier_event_id FROM e))
                SELECT
                  (SELECT count(*) FROM t JOIN master.registers r ON r.register_id = t.register_id WHERE r.store_id <> :s)
                + (SELECT count(*) FROM e JOIN master.registers r ON r.register_id = e.register_id WHERE r.store_id <> :s)
                + (SELECT count(*) FROM e JOIN master.cashier_versions v ON v.cashier_version_id = e.cashier_version_id
                                          JOIN master.cashiers c ON c.cashier_id = v.cashier_id WHERE c.store_id <> :s)
                + (SELECT count(*) FROM t JOIN master.house_account_versions v ON v.house_account_version_id = t.house_account_version_id
                                          JOIN master.house_accounts h ON h.house_account_id = v.house_account_id WHERE h.store_id <> :s)
                + (SELECT count(*) FROM cpj.payment_lines pl JOIN t ON t.ticket_id = pl.ticket_id
                                          JOIN master.house_account_versions v ON v.house_account_version_id = pl.house_account_version_id
                                          JOIN master.house_accounts h ON h.house_account_id = v.house_account_id WHERE h.store_id <> :s)
                + (SELECT count(*) FROM cpj.ticket_cashiers tc JOIN t ON t.ticket_id = tc.ticket_id
                                          JOIN master.cashier_versions v ON v.cashier_version_id = tc.cashier_version_id
                                          JOIN master.cashiers c ON c.cashier_id = v.cashier_id WHERE c.store_id <> :s)
                + (SELECT count(*) FROM cpj.cashier_sessions cs JOIN master.registers r ON r.register_id = cs.register_id
                                          WHERE cs.cpj_file_id = :f AND r.store_id <> :s)
                + (SELECT count(*) FROM l JOIN master.department_versions v ON v.department_version_id = l.department_version_id
                                          JOIN master.departments d ON d.department_id = v.department_id WHERE d.store_id <> :s)
                + (SELECT count(*) FROM l JOIN master.category_versions v ON v.category_version_id = l.category_version_id
                                          JOIN master.categories c ON c.category_id = v.category_id WHERE c.store_id <> :s)
                + (SELECT count(*) FROM l JOIN master.item_versions v ON v.item_version_id = l.item_version_id
                                          JOIN master.items i ON i.item_id = v.item_id WHERE i.store_id <> :s)
                + (SELECT count(*) FROM cpj.line_taxes x JOIN l ON l.line_id = x.line_id
                                          JOIN master.tax_versions v ON v.tax_version_id = x.tax_version_id
                                          JOIN master.taxes tx ON tx.tax_id = v.tax_id WHERE tx.store_id <> :s)
                + (SELECT count(*) FROM cpj.ticket_taxes x JOIN t ON t.ticket_id = x.ticket_id
                                          JOIN master.tax_versions v ON v.tax_version_id = x.tax_version_id
                                          JOIN master.taxes tx ON tx.tax_id = v.tax_id WHERE tx.store_id <> :s)
                + (SELECT count(*) FROM cpj.payment_lines p JOIN t ON t.ticket_id = p.ticket_id
                                          JOIN master.payment_type_versions v ON v.payment_type_version_id = p.payment_type_version_id
                                          JOIN master.payment_types pt ON pt.payment_type_id = v.payment_type_id WHERE pt.store_id <> :s)
                + (SELECT count(*) FROM cpj.payment_card_details c JOIN cpj.payment_lines p ON p.payment_line_id = c.payment_line_id
                                          JOIN t ON t.ticket_id = p.ticket_id JOIN master.cards k ON k.card_id = c.card_id WHERE k.store_id <> :s)
                + (SELECT count(*) FROM cpj.line_fuel x JOIN l ON l.line_id = x.line_id
                                          JOIN master.fuel_grade_versions v ON v.fuel_grade_version_id = x.fuel_grade_version_id
                                          JOIN master.fuel_grades g ON g.fuel_grade_id = v.fuel_grade_id WHERE g.store_id <> :s)
                + (SELECT count(*) FROM cpj.line_promotions x JOIN l ON l.line_id = x.line_id
                                          JOIN master.promotion_versions v ON v.promotion_version_id = x.promotion_version_id
                                          JOIN master.promotions pr ON pr.promotion_id = v.promotion_id WHERE pr.store_id <> :s)
                + (SELECT count(*) FROM cpj.ticket_loyalty x JOIN t ON t.ticket_id = x.ticket_id
                                          JOIN master.loyalty_programs lp ON lp.loyalty_program_id = x.loyalty_program_id WHERE lp.store_id <> :s)
                """.replace(":f", String.valueOf(f)).replace(":s", String.valueOf(store)), Long.class);
        return count == null ? 0 : count;
    }
}
