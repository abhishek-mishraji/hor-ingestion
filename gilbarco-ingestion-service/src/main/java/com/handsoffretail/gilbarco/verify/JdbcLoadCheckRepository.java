package com.handsoffretail.gilbarco.verify;

import java.sql.Types;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLoadCheckRepository implements LoadCheckRepository {

    private final JdbcTemplate jdbc;

    JdbcLoadCheckRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(long fetchRequestId, List<CheckResult> results) {
        jdbc.batchUpdate("""
                INSERT INTO ingest.load_checks (fetch_request_id, check_name, expected, actual, passed, hard, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, results.stream().map(r -> new Object[] {fetchRequestId, r.name(), r.expected().orElse(null),
                        r.actual().orElse(null), r.passed(), r.hard(), r.detail()}).toList(),
                new int[] {Types.BIGINT, Types.VARCHAR, Types.NUMERIC, Types.NUMERIC, Types.BOOLEAN, Types.BOOLEAN,
                        Types.VARCHAR});
    }

    @Override
    public List<CheckResult> findByFetch(long fetchRequestId) {
        return jdbc.query("""
                SELECT check_name, expected, actual, passed, hard, detail FROM ingest.load_checks
                WHERE fetch_request_id = ? ORDER BY check_id
                """, (rs, n) -> new CheckResult(rs.getString("check_name"), Optional.ofNullable(rs.getBigDecimal("expected")),
                        Optional.ofNullable(rs.getBigDecimal("actual")), rs.getBoolean("passed"), rs.getBoolean("hard"),
                        Optional.ofNullable(rs.getString("detail")).orElse("")),
                fetchRequestId);
    }
}
