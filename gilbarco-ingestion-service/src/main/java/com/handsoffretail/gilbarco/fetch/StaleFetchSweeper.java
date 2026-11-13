package com.handsoffretail.gilbarco.fetch;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.handsoffretail.gilbarco.config.IngestionProperties;

/**
 * Marks open (CLAIMED or RUNNING) fetches older than {@code hor.fetch.stale-after} (30 min) FAILED "interrupted": at
 * start-up (a crash left them) and every few minutes after, so an interrupted day never stays blocked
 * until the next restart. A real fetch takes seconds.
 */
@Component
class StaleFetchSweeper {

    private static final Logger log = LoggerFactory.getLogger(StaleFetchSweeper.class);
    private static final String MESSAGE = "interrupted: the service stopped or the fetch hung while it was running";

    private final FetchStatusRecorder recorder;
    private final Duration staleAfter;

    StaleFetchSweeper(FetchStatusRecorder recorder, IngestionProperties.Fetch fetch) {
        this.recorder = recorder;
        this.staleAfter = fetch.staleAfter();
    }

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT5M")
    void sweep() {
        int failed = recorder.failStale(staleAfter, MESSAGE);
        if (failed > 0) {
            log.warn("Marked {} interrupted fetch(es) FAILED", failed);
        }
    }
}
