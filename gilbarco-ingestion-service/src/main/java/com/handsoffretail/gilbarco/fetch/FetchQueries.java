package com.handsoffretail.gilbarco.fetch;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import com.handsoffretail.gilbarco.common.PageResult;
import com.handsoffretail.gilbarco.common.Paging;

/** Read side of fetches: history and the zip a fetch loaded. */
public interface FetchQueries {

    PageResult<FetchRequest> history(Optional<Long> storeId, Optional<LocalDate> from, Optional<LocalDate> to,
            Optional<String> fileType, Paging paging);

    Optional<Zip> zip(long s3ObjectId);

    /** The zip a fetch loaded. */
    record Zip(String bucket, String key, String fileType, int versionNo, String etag, long sizeBytes, Instant lastModified,
            Instant manifestCreatedAt, String mirrorVersion, long files) {
    }
}
