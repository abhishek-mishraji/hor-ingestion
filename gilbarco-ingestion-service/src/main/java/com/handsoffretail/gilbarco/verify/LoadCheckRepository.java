package com.handsoffretail.gilbarco.verify;

import java.util.List;

/** {@code ingest.load_checks}. */
public interface LoadCheckRepository {

    void save(long fetchRequestId, List<CheckResult> results);

    List<CheckResult> findByFetch(long fetchRequestId);
}
