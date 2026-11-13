package com.handsoffretail.gilbarco.filetypes.pjr.verify;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.handsoffretail.gilbarco.verify.CheckContext;
import com.handsoffretail.gilbarco.verify.CheckResult;

/**
 * Hard: one PJR file = one event. The files and events stored from this zip each equal the files in its
 * manifest, and every stored event is of the package's business day.
 */
@Component
@Order(110)
class PjrEventCountCheck implements PjrLoadCheck {

    private final JdbcTemplate jdbc;

    PjrEventCountCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CheckResult> run(CheckContext c) {
        long listed = c.zip().manifest().fileCount();
        long files = count("SELECT count(*) FROM pjr.pjr_file WHERE s3_object_id = ?", c.s3ObjectId());
        long events = count("SELECT count(*) FROM pjr.event e JOIN pjr.pjr_file f USING (file_id) WHERE f.s3_object_id = ?",
                c.s3ObjectId());
        long otherDay = count("SELECT count(*) FROM pjr.event e JOIN pjr.pjr_file f USING (file_id) "
                + "WHERE f.s3_object_id = ? AND e.business_date <> ?", c.s3ObjectId(), c.businessDate());
        return List.of(
                CheckResult.counted("pjr_files_equal_manifest", listed, files, true, "pjr.pjr_file rows vs manifest fileCount"),
                CheckResult.counted("pjr_events_equal_manifest", listed, events, true, "pjr.event rows (one per file) vs manifest fileCount"),
                CheckResult.counted("pjr_events_of_business_date", 0, otherDay, true,
                        "events whose BusinessDate is not " + c.businessDate()));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
