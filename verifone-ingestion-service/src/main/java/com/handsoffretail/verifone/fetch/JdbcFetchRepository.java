package com.handsoffretail.verifone.fetch;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.handsoffretail.verifone.common.PostgresErrors;
import com.handsoffretail.verifone.common.VerifoneErrorCode;

@Repository
class JdbcFetchRepository implements FetchRepository {

    static final String SELECT = """
            SELECT fetch_request_id, store_id, business_date, mode, requested_by, requested_at, status, s3_object_id,
                   finished_at, message
            FROM ingest.fetch_requests
            """;
    private static final int MESSAGE_MAX = 4000;

    private final JdbcClient jdbc;

    JdbcFetchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long claim(long storeId, LocalDate businessDate, FetchRequest.Mode mode, long requestedBy, Instant now) {
        try {
            return jdbc.sql("""
                    INSERT INTO ingest.fetch_requests (store_id, file_type, business_date, mode, requested_by, requested_at)
                    VALUES (:store, 'CPJ', :day, :mode, :by, :now)
                    RETURNING fetch_request_id
                    """)
                    .param("store", storeId).param("day", businessDate).param("mode", mode.name())
                    .param("by", requestedBy).param("now", utc(now))
                    .query(Long.class).single();
        } catch (DataIntegrityViolationException e) {
            if (PostgresErrors.constraint(e).filter("uq_fetch_requests_running"::equals).isPresent()) {
                throw VerifoneErrorCode.FETCH_RUNNING.exception("a fetch of this store and day is already running");
            }
            throw e;
        }
    }

    @Override
    public Optional<FetchRequest> find(long id) {
        return jdbc.sql(SELECT + "WHERE fetch_request_id = :id").param("id", id).query(JdbcFetchRepository::row).optional();
    }

    @Override
    public void finish(long id, FetchRequest.Status status, Optional<Long> s3ObjectId, String message, Instant now) {
        jdbc.sql("""
                UPDATE ingest.fetch_requests
                SET status = :status, s3_object_id = coalesce(:s3, s3_object_id), message = :message,
                    finished_at = greatest(:now, requested_at)
                WHERE fetch_request_id = :id AND status = 'RUNNING'
                """)
                .param("id", id).param("status", status.name()).param("s3", s3ObjectId.orElse(null))
                .param("message", truncate(message)).param("now", utc(now))
                .update();
    }

    @Override
    public int failStale(Instant before, Instant now, String message) {
        return jdbc.sql("""
                UPDATE ingest.fetch_requests SET status = 'FAILED', message = :message, finished_at = greatest(:now, requested_at)
                WHERE status = 'RUNNING' AND requested_at < :before
                """)
                .param("message", message).param("now", utc(now)).param("before", utc(before))
                .update();
    }

    static FetchRequest row(ResultSet rs, int row) throws SQLException {
        long s3 = rs.getLong("s3_object_id");
        Optional<Long> s3ObjectId = rs.wasNull() ? Optional.empty() : Optional.of(s3);
        return new FetchRequest(rs.getLong("fetch_request_id"), rs.getLong("store_id"),
                rs.getObject("business_date", LocalDate.class), FetchRequest.Mode.valueOf(rs.getString("mode")),
                rs.getLong("requested_by"), rs.getObject("requested_at", OffsetDateTime.class).toInstant(),
                FetchRequest.Status.valueOf(rs.getString("status")), s3ObjectId,
                Optional.ofNullable(rs.getObject("finished_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant),
                Optional.ofNullable(rs.getString("message")));
    }

    private static String truncate(String message) {
        return message.length() <= MESSAGE_MAX ? message : message.substring(0, MESSAGE_MAX - 3) + "...";
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
