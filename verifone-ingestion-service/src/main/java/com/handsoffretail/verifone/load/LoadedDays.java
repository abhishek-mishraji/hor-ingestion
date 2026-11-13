package com.handsoffretail.verifone.load;

import java.time.LocalDate;

/** What is loaded for a store's business day (cpj.cpj_files). */
public interface LoadedDays {

    boolean isLoaded(long storeId, LocalDate businessDate);

    /** REPLACE: deletes the day's files; the cascade removes everything under them. Returns files deleted. */
    int delete(long storeId, LocalDate businessDate);
}
