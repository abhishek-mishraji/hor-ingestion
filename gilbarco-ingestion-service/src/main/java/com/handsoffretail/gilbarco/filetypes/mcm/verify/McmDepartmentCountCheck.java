package com.handsoffretail.gilbarco.filetypes.mcm.verify;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.handsoffretail.gilbarco.verify.CheckContext;
import com.handsoffretail.gilbarco.verify.CheckResult;

/**
 * Hard: the department rows stored from this zip equal the rows the loader wrote (one per MCMDetail of the
 * store-level file), and every one is of the package's business day.
 */
@Component
@Order(210)
class McmDepartmentCountCheck implements McmLoadCheck {

    private static final String TABLE = "mcm.department_day";

    private final JdbcTemplate jdbc;

    McmDepartmentCountCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CheckResult> run(CheckContext c) {
        long written = c.rowsWritten().getOrDefault(TABLE, 0L);
        long stored = count("SELECT count(*) FROM mcm.department_day WHERE s3_object_id = ?", c.s3ObjectId());
        long otherDay = count("SELECT count(*) FROM mcm.department_day WHERE s3_object_id = ? AND business_date <> ?",
                c.s3ObjectId(), c.businessDate());
        return List.of(
                CheckResult.counted("mcm_departments_stored", written, stored, true,
                        "mcm.department_day rows vs MCMDetail elements of the store-level file"),
                CheckResult.counted("mcm_departments_of_business_date", 0, otherDay, true,
                        "department rows whose BusinessDate is not " + c.businessDate()));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
