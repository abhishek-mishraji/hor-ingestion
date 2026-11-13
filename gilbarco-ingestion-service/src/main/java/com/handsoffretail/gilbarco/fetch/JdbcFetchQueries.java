package com.handsoffretail.gilbarco.fetch;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.handsoffretail.gilbarco.common.PageResult;
import com.handsoffretail.gilbarco.common.Paging;

@Repository
class JdbcFetchQueries implements FetchQueries {

    private static final String FILTER = """
            WHERE (CAST(:store AS bigint) IS NULL OR store_id = :store)
              AND (CAST(:from AS date) IS NULL OR business_date >= :from)
              AND (CAST(:to AS date) IS NULL OR business_date <= :to)
              AND (CAST(:type AS text) IS NULL OR file_type = :type)
            """;

    private final JdbcClient jdbc;

    JdbcFetchQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PageResult<FetchRequest> history(Optional<Long> storeId, Optional<LocalDate> from, Optional<LocalDate> to,
            Optional<String> fileType, Paging paging) {
        List<FetchRequest> rows = filtered(JdbcFetchRepository.SELECT + FILTER
                + "ORDER BY requested_at DESC, fetch_request_id DESC LIMIT :limit OFFSET :offset", storeId, from, to, fileType)
                .param("limit", paging.limit()).param("offset", paging.offset())
                .query(JdbcFetchRepository::row).list();
        long total = filtered("SELECT count(*) FROM ingest.fetch_requests " + FILTER, storeId, from, to, fileType)
                .query(Long.class).single();
        return PageResult.of(rows, total, paging);
    }

    @Override
    public Optional<Zip> zip(long s3ObjectId) {
        return jdbc.sql("""
                SELECT bucket, object_key, file_type, version_no, etag, size_bytes, last_modified, manifest_created_at,
                       mirror_version, (SELECT count(*) FROM ingest.s3_object_files f WHERE f.s3_object_id = o.s3_object_id) AS files
                FROM ingest.s3_objects o WHERE s3_object_id = :id
                """).param("id", s3ObjectId)
                .query((rs, n) -> new Zip(rs.getString("bucket"), rs.getString("object_key"), rs.getString("file_type"),
                        rs.getInt("version_no"), rs.getString("etag"), rs.getLong("size_bytes"),
                        rs.getObject("last_modified", OffsetDateTime.class).toInstant(),
                        rs.getObject("manifest_created_at", OffsetDateTime.class).toInstant(), rs.getString("mirror_version"),
                        rs.getLong("files")))
                .optional();
    }

    private JdbcClient.StatementSpec filtered(String sql, Optional<Long> storeId, Optional<LocalDate> from,
            Optional<LocalDate> to, Optional<String> fileType) {
        return jdbc.sql(sql).param("store", storeId.orElse(null)).param("from", from.orElse(null))
                .param("to", to.orElse(null)).param("type", fileType.orElse(null));
    }
}
