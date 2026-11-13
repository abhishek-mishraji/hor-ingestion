package com.handsoffretail.verifone.days;

import java.sql.Array;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDayQueries implements DayQueries {

    private final JdbcClient jdbc;

    JdbcDayQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<LoadedDay> loadedDays(long storeId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT f.business_date, max(o.version_no) AS version_no, count(*) AS files,
                       array_agg(f.day_seq ORDER BY f.day_seq) AS day_seqs, max(f.loaded_at) AS loaded_at,
                       max(f.fetch_request_id) AS fetch_request_id
                FROM cpj.cpj_files f JOIN ingest.s3_objects o ON o.s3_object_id = f.s3_object_id
                WHERE f.store_id = :store AND f.business_date BETWEEN :from AND :to
                GROUP BY f.business_date ORDER BY f.business_date
                """)
                .param("store", storeId).param("from", from).param("to", to)
                .query((rs, n) -> new LoadedDay(rs.getObject("business_date", LocalDate.class), rs.getInt("version_no"),
                        rs.getInt("files"), ints(rs.getArray("day_seqs")),
                        rs.getObject("loaded_at", OffsetDateTime.class).toInstant(), rs.getLong("fetch_request_id")))
                .list();
    }

    private static List<Integer> ints(Array array) throws java.sql.SQLException {
        return Arrays.stream((Integer[]) array.getArray()).toList();
    }
}
