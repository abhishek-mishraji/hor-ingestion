package com.handsoffretail.verifone.fetch;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/** One click of Fetch: a store's business day, REPLACE or REFUSE, and where it ended. */
public record FetchRequest(long fetchRequestId, long storeId, LocalDate businessDate, Mode mode, long requestedBy,
        Instant requestedAt, Status status, Optional<Long> s3ObjectId, Optional<Instant> finishedAt,
        Optional<String> message) {

    public FetchRequest {
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(s3ObjectId, "s3ObjectId");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(message, "message");
    }

    /** REPLACE: delete what is loaded for the day and load; REFUSE: do nothing if anything is loaded. */
    public enum Mode {
        REPLACE,
        REFUSE
    }

    /** ck_fetch_requests_status. */
    public enum Status {
        RUNNING,
        LOADED,
        REFUSED,
        NOT_FOUND,
        FAILED
    }
}
