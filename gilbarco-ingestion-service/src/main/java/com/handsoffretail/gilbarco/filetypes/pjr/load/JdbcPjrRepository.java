package com.handsoffretail.gilbarco.filetypes.pjr.load;

import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.handsoffretail.gilbarco.filetypes.LoadedDays;

/**
 * The pjr schema: batch inserts of a day's rows (explicit SQL types, so a null never costs a metadata round
 * trip), department upserts, and the {@link LoadedDays} queries of the PJR file type.
 */
@Repository
public class JdbcPjrRepository implements LoadedDays {

    static final String PJR_FILE = "pjr.pjr_file";
    static final String EVENT = "pjr.event";
    static final String EVENT_LINE = "pjr.event_line";
    static final String TENDER_LINE = "pjr.tender_line";
    static final String EVENT_TAX = "pjr.event_tax";
    static final String DEPARTMENTS = "master.departments";

    /** Rows per JDBC batch (reWriteBatchedInserts turns each into multi-row INSERTs). */
    private static final int BATCH = 2_000;

    private static final String FILE_SQL = """
            INSERT INTO pjr.pjr_file (file_id, store_id, store_code, s3_object_id, file_name, sha256, size_bytes,
              written_at, file_counter, store_location, vendor_version, business_date, shift_begin)
            VALUES (?,?,?,?,?,?,?, ?,?,?,?,?,?)""";
    static final int[] FILE_TYPES = {Types.BIGINT, Types.BIGINT, Types.VARCHAR, Types.BIGINT, Types.VARCHAR, Types.VARCHAR,
        Types.INTEGER, Types.TIMESTAMP, Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.DATE, Types.TIMESTAMP};

    private static final String EVENT_SQL = """
            INSERT INTO pjr.event (file_id, store_id, store_code, business_date, event_type, detail_kind, transaction_id,
              register_id, cashier_id, till_id, started_at, ended_at, receipt_at, is_outside, is_suspended, is_training,
              is_offline, linked_register_id, linked_transaction_id, link_reason, loyalty_program,
              total_gross, total_net, total_tax, total_grand, grand_direction, detail_amount, payee, alert_code, reason)
            VALUES (?,?,?,?,?,?,?, ?,?,?,?,?,?,?,?,?, ?,?,?,?,?, ?,?,?,?,?,?,?,?,?)""";
    static final int[] EVENT_TYPES = {Types.BIGINT, Types.BIGINT, Types.VARCHAR, Types.DATE, Types.VARCHAR, Types.VARCHAR,
        Types.BIGINT, Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP, Types.TIMESTAMP, Types.TIMESTAMP,
        Types.BOOLEAN, Types.BOOLEAN, Types.BOOLEAN, Types.BOOLEAN, Types.INTEGER, Types.BIGINT, Types.VARCHAR,
        Types.VARCHAR, Types.NUMERIC, Types.NUMERIC, Types.NUMERIC, Types.NUMERIC, Types.VARCHAR, Types.NUMERIC,
        Types.VARCHAR, Types.VARCHAR, Types.VARCHAR};

    private static final String LINE_SQL = """
            INSERT INTO pjr.event_line (file_id, line_no, store_id, store_code, business_date, status, line_type,
              dept_number, pos_code, pos_code_format, pos_code_modifier, description, entry_method, quantity,
              selling_units, actual_price, regular_price, sales_amount, promotion_amount, discount_amount,
              promotion_reasons, tax_level, fuel_grade_id, fuel_position, price_tier, service_level)
            VALUES (?,?,?,?,?,?,?, ?,?,?,?,?,?,?, ?,?,?,?,?,?, ?,?,?,?,?,?)""";
    static final int[] LINE_TYPES = {Types.BIGINT, Types.SMALLINT, Types.BIGINT, Types.VARCHAR, Types.DATE, Types.VARCHAR,
        Types.VARCHAR, Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
        Types.NUMERIC, Types.NUMERIC, Types.NUMERIC, Types.NUMERIC, Types.NUMERIC, Types.NUMERIC, Types.NUMERIC,
        Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.SMALLINT, Types.VARCHAR, Types.VARCHAR};

    private static final String TENDER_SQL = """
            INSERT INTO pjr.tender_line (file_id, line_no, status, tender_code, tender_sub, amount, is_change, provider,
              authorized_amount)
            VALUES (?,?,?,?,?,?,?,?,?)""";
    static final int[] TENDER_TYPES = {Types.BIGINT, Types.SMALLINT, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
        Types.NUMERIC, Types.BOOLEAN, Types.VARCHAR, Types.NUMERIC};

    private static final String TAX_SQL = """
            INSERT INTO pjr.event_tax (file_id, line_no, status, tax_level, taxable_amount, tax_collected, taxable_refunded,
              tax_refunded, tax_exempt, tax_forgiven_sales)
            VALUES (?,?,?,?,?,?,?,?,?,?)""";
    static final int[] TAX_TYPES = {Types.BIGINT, Types.SMALLINT, Types.VARCHAR, Types.VARCHAR, Types.NUMERIC,
        Types.NUMERIC, Types.NUMERIC, Types.NUMERIC, Types.NUMERIC, Types.NUMERIC};

    // A department ring names its department; a scanned item only gives the number (its Description is the item).
    // pos_name always follows the files. The sales class follows the name (master.sales_class_for), so a department
    // named later is reclassified, unless a platform admin set its name and class (admin_set_at): then the
    // admin's values stay and only pos_name moves (V5).
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

    JdbcPjrRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code n} new pjr_file ids, in one round trip. */
    List<Long> nextFileIds(int n) {
        return jdbc.queryForList("SELECT nextval('pjr.pjr_file_file_id_seq') FROM generate_series(1, ?)", Long.class, n);
    }

    void insertFiles(List<Object[]> rows) {
        batch(FILE_SQL, rows, FILE_TYPES);
    }

    void insertEvents(List<Object[]> rows) {
        batch(EVENT_SQL, rows, EVENT_TYPES);
    }

    void insertLines(List<Object[]> rows) {
        batch(LINE_SQL, rows, LINE_TYPES);
    }

    void insertTenders(List<Object[]> rows) {
        batch(TENDER_SQL, rows, TENDER_TYPES);
    }

    void insertTaxes(List<Object[]> rows) {
        batch(TAX_SQL, rows, TAX_TYPES);
    }

    /** One row per department (a batch may not hit a row twice): store_id, store_code, number, name, name, name. */
    void upsertDepartments(List<Object[]> rows) {
        batch(DEPT_SQL, rows, DEPT_TYPES);
    }

    private void batch(String sql, List<Object[]> rows, int[] types) {
        for (int from = 0; from < rows.size(); from += BATCH) {
            jdbc.batchUpdate(sql, rows.subList(from, Math.min(rows.size(), from + BATCH)), types);
        }
    }

    // ------------------------------------------------------------------ LoadedDays

    @Override
    public boolean isLoaded(long storeId, LocalDate businessDate) {
        Boolean loaded = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pjr.pjr_file WHERE store_id = ? AND business_date = ?)",
                Boolean.class, storeId, businessDate);
        return Boolean.TRUE.equals(loaded);
    }

    @Override
    public int delete(long storeId, LocalDate businessDate) {
        return jdbc.update("DELETE FROM pjr.pjr_file WHERE store_id = ? AND business_date = ?", storeId, businessDate);
    }

    @Override
    public List<LoadedDay> loadedDays(long storeId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT f.business_date, max(o.version_no) AS version_no, count(*) AS files, max(f.loaded_at) AS loaded_at,
                       max(f.s3_object_id) AS s3_object_id
                FROM pjr.pjr_file f JOIN ingest.s3_objects o ON o.s3_object_id = f.s3_object_id
                WHERE f.store_id = ? AND f.business_date BETWEEN ? AND ?
                GROUP BY f.business_date ORDER BY f.business_date
                """, (rs, n) -> new LoadedDay(rs.getObject("business_date", LocalDate.class), rs.getInt("version_no"),
                        rs.getLong("files"), rs.getObject("loaded_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("s3_object_id")),
                storeId, from, to);
    }

    @Override
    public Map<String, Long> rowCounts(long s3ObjectId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM pjr.pjr_file WHERE s3_object_id = :o) AS files,
                       (SELECT count(*) FROM pjr.event e JOIN pjr.pjr_file f USING (file_id) WHERE f.s3_object_id = :o) AS events,
                       (SELECT count(*) FROM pjr.event_line l JOIN pjr.pjr_file f USING (file_id) WHERE f.s3_object_id = :o) AS lines,
                       (SELECT count(*) FROM pjr.tender_line t JOIN pjr.pjr_file f USING (file_id) WHERE f.s3_object_id = :o) AS tenders,
                       (SELECT count(*) FROM pjr.event_tax x JOIN pjr.pjr_file f USING (file_id) WHERE f.s3_object_id = :o) AS taxes
                """.replace(":o", Long.toString(s3ObjectId)));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(PJR_FILE, ((Number) row.get("files")).longValue());
        counts.put(EVENT, ((Number) row.get("events")).longValue());
        counts.put(EVENT_LINE, ((Number) row.get("lines")).longValue());
        counts.put(TENDER_LINE, ((Number) row.get("tenders")).longValue());
        counts.put(EVENT_TAX, ((Number) row.get("taxes")).longValue());
        return counts;
    }
}
