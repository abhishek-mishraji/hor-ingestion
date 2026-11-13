package com.handsoffretail.gilbarco.fetch;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.handsoffretail.gilbarco.common.GilbarcoErrorCode;
import com.handsoffretail.gilbarco.common.PageResult;
import com.handsoffretail.gilbarco.common.Paging;
import com.handsoffretail.gilbarco.filetypes.FileType;
import com.handsoffretail.gilbarco.filetypes.FileTypeRegistry;
import com.handsoffretail.gilbarco.verify.CheckResult;
import com.handsoffretail.gilbarco.verify.LoadCheckRepository;

/** Use cases: a fetch's detail (zip, rows the file type holds from it, load checks) and the fetch history. */
@Service
public class FetchQueryService {

    private final FetchRepository fetches;
    private final FetchQueries queries;
    private final LoadCheckRepository checks;
    private final FileTypeRegistry fileTypes;

    public FetchQueryService(FetchRepository fetches, FetchQueries queries, LoadCheckRepository checks,
            FileTypeRegistry fileTypes) {
        this.fetches = fetches;
        this.queries = queries;
        this.checks = checks;
        this.fileTypes = fileTypes;
    }

    @Transactional(readOnly = true)
    public Detail detail(long fetchRequestId) {
        FetchRequest request = fetches.find(fetchRequestId)
                .orElseThrow(() -> GilbarcoErrorCode.notFound("fetch", fetchRequestId));
        Optional<FetchQueries.Zip> zip = request.s3ObjectId().flatMap(queries::zip);
        Map<String, Long> rows = request.s3ObjectId()
                .map(id -> fileTypes.require(request.fileType()).days().rowCounts(id)).orElse(Map.of());
        return new Detail(request, zip, rows, checks.findByFetch(fetchRequestId));
    }

    /** @param fileType empty = every type */
    @Transactional(readOnly = true)
    public PageResult<FetchRequest> history(Optional<Long> storeId, Optional<LocalDate> from, Optional<LocalDate> to,
            Optional<String> fileType, Paging paging) {
        return queries.history(storeId, from, to, fileType.map(FileType::normalize), paging);
    }

    /** A fetch with what it loaded. */
    public record Detail(FetchRequest request, Optional<FetchQueries.Zip> zip, Map<String, Long> rows,
            List<CheckResult> checks) {

        public Detail {
            rows = Map.copyOf(rows);
            checks = List.copyOf(checks);
        }
    }
}
