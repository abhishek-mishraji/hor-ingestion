package com.handsoffretail.gilbarco.verify;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

import com.handsoffretail.gilbarco.stores.StoreRef;

/**
 * Everything a load check may look at: the fetch, the store's day, the zip record it came from, the verified zip
 * and the rows the file-type processor wrote (per table).
 */
public record CheckContext(long fetchRequestId, StoreRef store, LocalDate businessDate, String fileType, long s3ObjectId,
        VerifiedZip zip, Map<String, Long> rowsWritten) {

    public CheckContext {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(fileType, "fileType");
        Objects.requireNonNull(zip, "zip");
        rowsWritten = Map.copyOf(rowsWritten);
    }
}
