package com.handsoffretail.verifone.fetch;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.handsoffretail.verifone.common.PageResult;
import com.handsoffretail.verifone.common.Paging;

@Repository
class JdbcFetchQueries implements FetchQueries {

    private static final String FILTER = """
            WHERE (CAST(:store AS bigint) IS NULL OR store_id = :store)
              AND (CAST(:from AS date) IS NULL OR business_date >= :from)
              AND (CAST(:to AS date) IS NULL OR business_date <= :to)
            """;
    /** Rows per kind of one file, under the names the admin console shows. */
    private static final String ROW_COUNTS = """
            SELECT (SELECT count(*) FROM cpj.tickets WHERE cpj_file_id = :f) AS tickets,
                   (SELECT count(*) FROM cpj.ticket_lines l JOIN cpj.tickets t USING (ticket_id) WHERE t.cpj_file_id = :f) AS ticket_lines,
                   (SELECT count(*) FROM cpj.ticket_lines l JOIN cpj.cashier_events e USING (cashier_event_id) WHERE e.cpj_file_id = :f) AS event_lines,
                   (SELECT count(*) FROM cpj.payment_lines p JOIN cpj.tickets t USING (ticket_id) WHERE t.cpj_file_id = :f) AS payment_lines,
                   (SELECT count(*) FROM cpj.payment_card_details c JOIN cpj.payment_lines p USING (payment_line_id)
                                     JOIN cpj.tickets t USING (ticket_id) WHERE t.cpj_file_id = :f) AS card_payments,
                   (SELECT count(*) FROM cpj.ticket_age_checks a JOIN cpj.tickets t USING (ticket_id) WHERE t.cpj_file_id = :f) AS age_checks,
                   (SELECT count(*) FROM cpj.cashier_events WHERE cpj_file_id = :f) AS cashier_events,
                   (SELECT count(*) FROM cpj.cashier_sessions WHERE cpj_file_id = :f) AS cashier_sessions,
                   (SELECT count(*) FROM cpj.period_closes WHERE cpj_file_id = :f) AS period_closes,
                   (SELECT count(*) FROM cpj.ticket_taxes x JOIN cpj.tickets t USING (ticket_id) WHERE t.cpj_file_id = :f) AS ticket_taxes,
                   (SELECT count(*) FROM cpj.line_fuel x JOIN cpj.ticket_lines l USING (line_id) JOIN cpj.tickets t USING (ticket_id)
                                     WHERE t.cpj_file_id = :f) AS fuel_lines,
                   (SELECT count(*) FROM cpj.ticket_loyalty x JOIN cpj.tickets t USING (ticket_id) WHERE t.cpj_file_id = :f) AS loyalty,
                   (SELECT count(*) FROM cpj.ticket_coupons x JOIN cpj.tickets t USING (ticket_id) WHERE t.cpj_file_id = :f) AS coupons
            """;

    private final JdbcClient jdbc;

    JdbcFetchQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PageResult<FetchRequest> history(Optional<Long> storeId, Optional<LocalDate> from, Optional<LocalDate> to,
            Paging paging) {
        List<FetchRequest> rows = filtered(JdbcFetchRepository.SELECT + FILTER
                + "ORDER BY requested_at DESC, fetch_request_id DESC LIMIT :limit OFFSET :offset", storeId, from, to)
                .param("limit", paging.limit()).param("offset", paging.offset())
                .query(JdbcFetchRepository::row).list();
        long total = filtered("SELECT count(*) FROM ingest.fetch_requests " + FILTER, storeId, from, to)
                .query(Long.class).single();
        return PageResult.of(rows, total, paging);
    }

    @Override
    public Optional<Zip> zip(long s3ObjectId) {
        return jdbc.sql("""
                SELECT bucket, object_key, version_no, etag, size_bytes, last_modified, manifest_created_at, mirror_version
                FROM ingest.s3_objects WHERE s3_object_id = :id
                """).param("id", s3ObjectId)
                .query((rs, n) -> new Zip(rs.getString("bucket"), rs.getString("object_key"), rs.getInt("version_no"),
                        rs.getString("etag"), rs.getLong("size_bytes"),
                        rs.getObject("last_modified", OffsetDateTime.class).toInstant(),
                        rs.getObject("manifest_created_at", OffsetDateTime.class).toInstant(), rs.getString("mirror_version")))
                .optional();
    }

    @Override
    public List<LoadedFile> files(long fetchRequestId) {
        return jdbc.sql("SELECT cpj_file_id, file_path, day_seq, loaded_at FROM cpj.cpj_files WHERE fetch_request_id = :id ORDER BY day_seq")
                .param("id", fetchRequestId)
                .query((rs, n) -> {
                    long fileId = rs.getLong("cpj_file_id");
                    return new LoadedFile(fileId, rs.getString("file_path"), rs.getInt("day_seq"),
                            rs.getObject("loaded_at", OffsetDateTime.class).toInstant(), rowCounts(fileId), checks(fileId));
                })
                .list();
    }

    private Map<String, Long> rowCounts(long fileId) {
        Map<String, Object> row = jdbc.sql(ROW_COUNTS).param("f", fileId).query().singleRow();
        Map<String, Long> counts = new LinkedHashMap<>();
        row.forEach((k, v) -> counts.put(k, ((Number) v).longValue()));
        return counts;
    }

    private List<Check> checks(long fileId) {
        return jdbc.sql("SELECT check_name, expected, actual, passed, detail FROM cpj.load_checks WHERE cpj_file_id = :f ORDER BY check_name")
                .param("f", fileId)
                .query((rs, n) -> {
                    String detail = rs.getString("detail");
                    boolean hard = detail != null && detail.startsWith("hard");
                    String text = detail == null ? "" : detail.replaceFirst("^(hard|soft)(: )?", "");
                    return new Check(rs.getString("check_name"), Optional.ofNullable(rs.getBigDecimal("expected")),
                            Optional.ofNullable(rs.getBigDecimal("actual")), rs.getBoolean("passed"), hard, text);
                })
                .list();
    }

    private JdbcClient.StatementSpec filtered(String sql, Optional<Long> storeId, Optional<LocalDate> from,
            Optional<LocalDate> to) {
        return jdbc.sql(sql).param("store", storeId.orElse(null)).param("from", from.orElse(null)).param("to", to.orElse(null));
    }
}
