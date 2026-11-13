package com.handsoffretail.verifone.days;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Business days loaded for a store (cpj.cpj_files with their zip). */
public interface DayQueries {

    List<LoadedDay> loadedDays(long storeId, LocalDate from, LocalDate to);

    /** One loaded business day: its zip version, files (closes) and when it was loaded. */
    record LoadedDay(LocalDate businessDate, int versionNo, int files, List<Integer> daySeqs, Instant loadedAt,
            long fetchRequestId) {

        public LoadedDay {
            daySeqs = List.copyOf(daySeqs);
        }
    }
}
