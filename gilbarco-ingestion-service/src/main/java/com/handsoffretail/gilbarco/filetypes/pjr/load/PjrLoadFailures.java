package com.handsoffretail.gilbarco.filetypes.pjr.load;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * pjr.load_failure: written in its own transaction, because the day's load that hit the bad file is rolled
 * back (owner, 2026-10-23: one unparseable file fails the whole day; nothing is half-loaded).
 */
@Service
public class PjrLoadFailures {

    private static final int ERROR_MAX = 4000;

    private final JdbcTemplate jdbc;

    public PjrLoadFailures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long storeId, String storeCode, long fetchRequestId, String fileName, String sha256, String error) {
        jdbc.update("""
                INSERT INTO pjr.load_failure (store_id, store_code, fetch_request_id, file_name, sha256, error)
                VALUES (?, ?, ?, ?, ?, ?)
                """, storeId, storeCode, fetchRequestId, fileName, sha256,
                error.length() <= ERROR_MAX ? error : error.substring(0, ERROR_MAX - 3) + "...");
    }
}
