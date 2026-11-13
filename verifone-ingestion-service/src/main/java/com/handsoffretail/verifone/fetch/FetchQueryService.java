package com.handsoffretail.verifone.fetch;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.handsoffretail.verifone.common.PageResult;
import com.handsoffretail.verifone.common.Paging;
import com.handsoffretail.verifone.common.VerifoneErrorCode;

/** Use cases: a fetch's detail (zip, files, rows per kind, load checks) and the fetch history. */
@Service
public class FetchQueryService {

    private final FetchRepository fetches;
    private final FetchQueries queries;

    public FetchQueryService(FetchRepository fetches, FetchQueries queries) {
        this.fetches = fetches;
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public Detail detail(long fetchRequestId) {
        FetchRequest request = fetches.find(fetchRequestId)
                .orElseThrow(() -> VerifoneErrorCode.notFound("fetch", fetchRequestId));
        return new Detail(request, request.s3ObjectId().flatMap(queries::zip), queries.files(fetchRequestId));
    }

    @Transactional(readOnly = true)
    public PageResult<FetchRequest> history(Optional<Long> storeId, Optional<LocalDate> from, Optional<LocalDate> to,
            Paging paging) {
        return queries.history(storeId, from, to, paging);
    }

    /** A fetch with what it loaded. */
    public record Detail(FetchRequest request, Optional<FetchQueries.Zip> zip, List<FetchQueries.LoadedFile> files) {

        public Detail {
            files = List.copyOf(files);
        }
    }
}
