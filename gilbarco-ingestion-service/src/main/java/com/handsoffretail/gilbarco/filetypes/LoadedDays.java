package com.handsoffretail.gilbarco.filetypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** What one file type has loaded for a store's business days (its own tables). */
public interface LoadedDays {

    boolean isLoaded(long storeId, LocalDate businessDate);

    /** REPLACE: deletes the day's rows of this type (cascades). Returns the top-level rows deleted. */
    int delete(long storeId, LocalDate businessDate);

    List<LoadedDay> loadedDays(long storeId, LocalDate from, LocalDate to);

    /** Rows of this type currently stored from one zip, per table (for the fetch detail). */
    Map<String, Long> rowCounts(long s3ObjectId);

    /** One loaded business day: its zip version, files and when it was loaded. */
    record LoadedDay(LocalDate businessDate, int versionNo, long files, Instant loadedAt, long s3ObjectId) {

        public LoadedDay {
            Objects.requireNonNull(businessDate, "businessDate");
            Objects.requireNonNull(loadedAt, "loadedAt");
        }
    }
}
