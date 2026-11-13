package com.handsoffretail.verifone.fetch;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.handsoffretail.verifone.stores.StoreRef;

/**
 * The short transactions of a fetch, each committed on its own: the claim (RUNNING), and the end states written
 * outside the load transaction (NOT_FOUND, an early REFUSED, FAILED after a rolled-back load).
 * The store row already exists in master.stores (store sync), so the claim no longer copies it.
 */
@Service
public class FetchStatusRecorder {

    private final FetchRepository fetches;
    private final Clock clock;

    public FetchStatusRecorder(FetchRepository fetches, Clock clock) {
        this.fetches = fetches;
        this.clock = clock;
    }

    /** Inserts the RUNNING row. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long claim(StoreRef store, LocalDate businessDate, FetchRequest.Mode mode, long requestedBy) {
        return fetches.claim(store.storeId(), businessDate, mode, requestedBy, clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void end(long fetchRequestId, FetchRequest.Status status, String message) {
        fetches.finish(fetchRequestId, status, Optional.empty(), message, clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int failStale(Duration olderThan, String message) {
        return fetches.failStale(clock.instant().minus(olderThan), clock.instant(), message);
    }
}
