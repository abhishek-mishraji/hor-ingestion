package com.handsoffretail.verifone.fetch;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** ingest.fetch_requests. */
public interface FetchRepository {

    /**
     * Inserts a RUNNING fetch.
     *
     * @throws com.handsoffretail.auth.client.DomainException (FETCH_RUNNING) when a fetch of that store and day is
     *         already running (uq_fetch_requests_running)
     */
    long claim(long storeId, LocalDate businessDate, FetchRequest.Mode mode, long requestedBy, Instant now);

    Optional<FetchRequest> find(long fetchRequestId);

    /** Ends a RUNNING fetch; ignored when the fetch has already ended. */
    void finish(long fetchRequestId, FetchRequest.Status status, Optional<Long> s3ObjectId, String message, Instant now);

    /** Marks RUNNING fetches requested before {@code before} as FAILED. Returns how many. */
    int failStale(Instant before, Instant now, String message);
}
