package com.handsoffretail.verifone.source;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** One day's CPJ zip in the bucket (or in the local folder laid out like it). */
public record ZipObject(String key, String etag, long sizeBytes, Instant lastModified, LocalDate businessDate,
        int versionNo) {

    public ZipObject {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(etag, "etag");
        Objects.requireNonNull(lastModified, "lastModified");
        Objects.requireNonNull(businessDate, "businessDate");
        if (versionNo < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
    }

    /** The file name (last key segment). */
    public String fileName() {
        return key.substring(key.lastIndexOf('/') + 1);
    }
}
