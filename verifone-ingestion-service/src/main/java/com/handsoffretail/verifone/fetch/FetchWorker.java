package com.handsoffretail.verifone.fetch;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.handsoffretail.verifone.common.VerifoneErrorCode;

/**
 * Runs fetches on the small fetch executor. The only place that catches every exception:
 * whatever ends a fetch abnormally is recorded as FAILED.
 */
@Component
class FetchWorker {

    private static final Logger log = LoggerFactory.getLogger(FetchWorker.class);

    private final Executor executor;
    private final FetchStatusRecorder recorder;

    FetchWorker(@Qualifier("fetchExecutor") Executor executor, FetchStatusRecorder recorder) {
        this.executor = executor;
        this.recorder = recorder;
    }

    void submit(long fetchRequestId, Runnable fetch) {
        try {
            executor.execute(() -> run(fetchRequestId, fetch));
        } catch (RejectedExecutionException e) {
            recorder.end(fetchRequestId, FetchRequest.Status.FAILED, "too many fetches waiting; try again in a minute");
            throw VerifoneErrorCode.FETCH_QUEUE_FULL.exception("too many fetches are waiting; try again in a minute");
        }
    }

    private void run(long fetchRequestId, Runnable fetch) {
        try {
            fetch.run();
        } catch (Exception e) {
            String message = FailureMessages.of(e);
            if (FailureMessages.isExpected(e)) {
                log.warn("Fetch {} failed: {}", fetchRequestId, message);
            } else {
                log.error("Fetch {} failed unexpectedly", fetchRequestId, e);
            }
            recorder.end(fetchRequestId, FetchRequest.Status.FAILED, message);
        }
    }
}
