package com.handsoffretail.gilbarco.filetypes;

import java.time.LocalDate;
import java.util.Objects;

import com.handsoffretail.gilbarco.source.ZipObject;
import com.handsoffretail.gilbarco.stores.StoreRef;
import com.handsoffretail.gilbarco.verify.VerifiedZip;

/**
 * What a {@link FileTypeProcessor} loads: a verified day package of one store, already recorded as
 * {@code ingest.s3_objects} row {@code s3ObjectId}.
 */
public record FileLoadContext(long fetchRequestId, StoreRef store, LocalDate businessDate, long s3ObjectId,
        ZipObject zip, VerifiedZip verified) {

    public FileLoadContext {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(zip, "zip");
        Objects.requireNonNull(verified, "verified");
    }
}
