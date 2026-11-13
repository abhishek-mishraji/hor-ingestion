package com.handsoffretail.gilbarco.fetch;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** ingest.fetch_requests. */
public interface FetchRepository {

    /**
     * Inserts a CLAIMED fetch.
     *
     * @throws com.handsoffretail.auth.client.DomainException (FETCH_RUNNING) when a fetch of that store, day and
     *         file type is already open (uq_fetch_requests_running)
     */
    long claim(long storeId, LocalDate businessDate, String fileType, FetchRequest.Mode mode, long requestedBy, Instant now);

    Optional<FetchRequest> find(long fetchRequestId);

    /** CLAIMED -> RUNNING; false when the fetch is no longer CLAIMED (swept or ended). */
    boolean markRunning(long fetchRequestId);

    /** Ends an open fetch; ignored when the fetch has already ended. */
    void finish(long fetchRequestId, FetchRequest.Status status, Optional<Long> s3ObjectId, String message, Instant now);

    /** Marks open fetches requested before {@code before} as FAILED. Returns how many. */
    int failStale(Instant before, Instant now, String message);
}
