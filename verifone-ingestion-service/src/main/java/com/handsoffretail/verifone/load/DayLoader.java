package com.handsoffretail.verifone.load;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.handsoffretail.verifone.source.ZipObject;
import com.handsoffretail.verifone.stores.StoreRef;
import com.handsoffretail.verifone.verify.CheckResult;
import com.handsoffretail.verifone.verify.VerifiedZip;

/**
 * Loads one verified zip as the business day of a store. Runs inside the caller's transaction (the
 * load transaction of database-design.md §2): REFUSE / REPLACE, zip rows, every CPJ file, load checks.
 */
public interface DayLoader {

    /**
     * @throws com.handsoffretail.verifone.parse.CpjFormatException     unknown or inconsistent input
     * @throws com.handsoffretail.verifone.verify.LoadCheckFailedException a hard load check failed
     */
    Outcome load(Request request);

    /** What to load. {@code replace} = delete the store's day first; otherwise refuse if anything is loaded. */
    record Request(long fetchRequestId, StoreRef store, LocalDate businessDate, boolean replace, String bucket,
            ZipObject zip, VerifiedZip verified) {

        public Request {
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(zip, "zip");
            Objects.requireNonNull(verified, "verified");
        }
    }

    /** The result: refused (already loaded) or loaded with each file's row counts and checks. */
    sealed interface Outcome {
    }

    record Refused(String reason) implements Outcome {
    }

    record Loaded(long s3ObjectId, List<FileLoad> files) implements Outcome {

        public Loaded {
            files = List.copyOf(files);
        }
    }

    record FileLoad(long cpjFileId, String filePath, Map<String, Integer> rows, List<CheckResult> checks) {

        public FileLoad {
            rows = Map.copyOf(rows);
            checks = List.copyOf(checks);
        }
    }
}
