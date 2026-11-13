package com.handsoffretail.gilbarco.filetypes.mcm.load;

import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.handsoffretail.gilbarco.filetypes.LoadedDays;

/** The mcm schema (one table, mcm.department_day), department-name upserts, and the MCM {@link LoadedDays}. */
@Repository
public class JdbcMcmRepository implements LoadedDays {

    static final String DEPARTMENT_DAY = "mcm.department_day";
    static final String DEPARTMENTS = "master.departments";

    private static final String DAY_SQL = """
            INSERT INTO mcm.department_day (store_id, store_code, business_date, dept_number, dept_name, sales_amount,
              sales_quantity, promotion_amount, promotion_count, discount_amount, discount_count, refund_amount,
              refund_count, transaction_count, open_dept_sales_amount, open_dept_transaction_count, s3_object_id,
              file_name, sha256, period_begin, period_end)
            VALUES (?,?,?,?,?,?, ?,?,?,?,?,?, ?,?,?,?,?, ?,?,?,?)""";
    static final int[] DAY_TYPES = {Types.BIGINT, Types.VARCHAR, Types.DATE, Types.INTEGER, Types.VARCHAR, Types.NUMERIC,
        Types.NUMERIC, Types.NUMERIC, Types.INTEGER, Types.NUMERIC, Types.INTEGER, Types.NUMERIC,
        Types.INTEGER, Types.INTEGER, Types.NUMERIC, Types.INTEGER, Types.BIGINT,
        Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP, Types.TIMESTAMP};

    // The same rule as the PJR loader (V5): pos_name always follows the files; dept_name and sales_class follow
    // pos_name unless a platform admin set them (admin_set_at). MCM names every department the day sold,
    // including the ones PJR only ever scans. A department MCM lists without a name is left as it is.
    private static final String DEPT_SQL = """
            INSERT INTO master.departments (store_id, store_code, dept_number, dept_name, pos_name, sales_class)
            VALUES (?, ?, ?, ?, ?, master.sales_class_for(?))
            ON CONFLICT (store_id, dept_number) DO UPDATE
            SET pos_name    = excluded.pos_name,
                dept_name   = CASE WHEN master.departments.admin_set_at IS NULL THEN excluded.pos_name
                                   ELSE master.departments.dept_name END,
                sales_class = CASE WHEN master.departments.admin_set_at IS NULL THEN master.sales_class_for(excluded.pos_name)
                                   ELSE master.departments.sales_class END,
                updated_at  = now()
            WHERE excluded.pos_name IS NOT NULL
              AND excluded.pos_name IS DISTINCT FROM master.departments.pos_name""";
    static final int[] DEPT_TYPES = {Types.BIGINT, Types.VARCHAR, Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR};

    private final JdbcTemplate jdbc;

    JdbcMcmRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insertDepartmentDays(List<Object[]> rows) {
        jdbc.batchUpdate(DAY_SQL, rows, DAY_TYPES);
    }

    /** One row per department, in number order: store_id, store_code, number, name, name, name. */
    void upsertDepartments(List<Object[]> rows) {
        jdbc.batchUpdate(DEPT_SQL, rows, DEPT_TYPES);
    }

    // ------------------------------------------------------------------ LoadedDays

    @Override
    public boolean isLoaded(long storeId, LocalDate businessDate) {
        Boolean loaded = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM mcm.department_day WHERE store_id = ? AND business_date = ?)",
                Boolean.class, storeId, businessDate);
        return Boolean.TRUE.equals(loaded);
    }

    @Override
    public int delete(long storeId, LocalDate businessDate) {
        return jdbc.update("DELETE FROM mcm.department_day WHERE store_id = ? AND business_date = ?", storeId, businessDate);
    }

    /** files = the stored MCM files of the day (one: the store-level file). */
    @Override
    public List<LoadedDay> loadedDays(long storeId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT d.business_date, max(o.version_no) AS version_no, count(DISTINCT d.file_name) AS files,
                       max(d.loaded_at) AS loaded_at, max(d.s3_object_id) AS s3_object_id
                FROM mcm.department_day d JOIN ingest.s3_objects o ON o.s3_object_id = d.s3_object_id
                WHERE d.store_id = ? AND d.business_date BETWEEN ? AND ?
                GROUP BY d.business_date ORDER BY d.business_date
                """, (rs, n) -> new LoadedDay(rs.getObject("business_date", LocalDate.class), rs.getInt("version_no"),
                        rs.getLong("files"), rs.getObject("loaded_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("s3_object_id")),
                storeId, from, to);
    }

    @Override
    public Map<String, Long> rowCounts(long s3ObjectId) {
        Long rows = jdbc.queryForObject("SELECT count(*) FROM mcm.department_day WHERE s3_object_id = ?", Long.class,
                s3ObjectId);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(DEPARTMENT_DAY, rows == null ? 0 : rows);
        return counts;
    }
}
