package com.handsoffretail.verifone.fetch;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.handsoffretail.verifone.common.PageResult;
import com.handsoffretail.verifone.common.Paging;

/** Read side of fetches: history and the detail panel (zip, files, rows per kind, load checks). */
public interface FetchQueries {

    PageResult<FetchRequest> history(Optional<Long> storeId, Optional<LocalDate> from, Optional<LocalDate> to, Paging paging);

    Optional<Zip> zip(long s3ObjectId);

    List<LoadedFile> files(long fetchRequestId);

    /** The zip a fetch loaded. */
    record Zip(String bucket, String key, int versionNo, String etag, long sizeBytes, Instant lastModified,
            Instant manifestCreatedAt, String mirrorVersion) {
    }

    /** One CPJ file the fetch loaded, with its stored rows per kind and its load checks. */
    record LoadedFile(long cpjFileId, String filePath, int daySeq, Instant loadedAt, Map<String, Long> rows,
            List<Check> checks) {

        public LoadedFile {
            rows = Map.copyOf(rows);
            checks = List.copyOf(checks);
        }
    }

    /** One cpj.load_checks row. */
    record Check(String name, Optional<BigDecimal> expected, Optional<BigDecimal> actual, boolean passed, boolean hard,
            String detail) {
    }
}
