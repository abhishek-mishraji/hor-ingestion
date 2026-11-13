package com.handsoffretail.verifone.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** {@code cpj.load_checks} and the stored-row counts / sums the checks compare with. */
public interface LoadCheckRepository {

    void save(long cpjFileId, List<CheckResult> results);

    /** Rows stored for the file, under the names the count reconciliation uses. */
    Map<String, Long> storedCounts(long cpjFileId);

    /** END − START of the never-resetting sales counter, if both totals exist. */
    BigDecimal counterChange(long cpjFileId);

    /** Total of the tickets that count as sales (the PROVEN rule of IMPLEMENTATION_PLAN.md Phase 4). */
    BigDecimal countedSalesTotal(long cpjFileId);

    BigDecimal storedCouponSum(long cpjFileId);

    /** References from the file's rows to codes of another store (must be 0). */
    long foreignCodeReferences(long cpjFileId, long storeId);
}
