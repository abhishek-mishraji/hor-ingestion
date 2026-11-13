package com.handsoffretail.gilbarco.fetch;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.handsoffretail.auth.client.CommonErrorCode;
import com.handsoffretail.auth.client.DomainException;
import com.handsoffretail.auth.client.HorPrincipal;
import com.handsoffretail.gilbarco.common.PageResult;
import com.handsoffretail.gilbarco.common.Paging;
import com.handsoffretail.gilbarco.stores.StoreDirectory;

@RestController
@RequestMapping("/api/ingestion/gilbarco/fetches")
class FetchController {

    private final FetchService fetches;
    private final FetchQueryService queries;
    private final StoreDirectory stores;

    FetchController(FetchService fetches, FetchQueryService queries, StoreDirectory stores) {
        this.fetches = fetches;
        this.queries = queries;
        this.stores = stores;
    }

    /** Starts a background fetch: 202 {@code {fetchRequestId}}. {@code fileType} defaults to PJR. */
    @PostMapping
    ResponseEntity<Map<String, Long>> start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StartFetchRequest request) {
        HorPrincipal caller = HorPrincipal.from(jwt);
        stores.require(caller, request.storeId());
        long id = fetches.start(request.storeId(), request.businessDate(), request.mode(), request.fileType(), caller.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("fetchRequestId", id));
    }

    @GetMapping("/{id}")
    FetchDetailResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        FetchQueryService.Detail detail = queries.detail(id);
        stores.require(HorPrincipal.from(jwt), detail.request().storeId());
        return FetchDetailResponse.from(detail);
    }

    @GetMapping
    PageResult<FetchResponse> history(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long storeId, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) String fileType,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        HorPrincipal caller = HorPrincipal.from(jwt);
        if (storeId != null) {
            stores.require(caller, storeId);
        } else if (!caller.allStores()) {
            // Only platform admins today; a store-scoped caller must name a store.
            throw new DomainException(CommonErrorCode.FORBIDDEN, "storeId is required");
        }
        return queries.history(Optional.ofNullable(storeId), Optional.ofNullable(from), Optional.ofNullable(to),
                Optional.ofNullable(fileType).filter(t -> !t.isBlank()), Paging.of(limit, offset)).map(FetchResponse::from);
    }

    record StartFetchRequest(@NotNull @Positive Long storeId, @NotNull LocalDate businessDate,
            @NotNull FetchRequest.Mode mode, String fileType) {
    }

    record FetchResponse(long id, long storeId, LocalDate businessDate, String fileType, FetchRequest.Mode mode,
            FetchRequest.Status status, String message, long requestedBy, Instant requestedAt, Instant finishedAt,
            Long durationMs) {

        static FetchResponse from(FetchRequest r) {
            return new FetchResponse(r.fetchRequestId(), r.storeId(), r.businessDate(), r.fileType(), r.mode(), r.status(),
                    r.message().orElse(null), r.requestedBy(), r.requestedAt(), r.finishedAt().orElse(null),
                    r.finishedAt().map(f -> Duration.between(r.requestedAt(), f).toMillis()).orElse(null));
        }
    }

    record ZipResponse(String bucket, String key, String fileType, int version, String etag, long sizeBytes,
            Instant lastModified, Instant manifestCreatedAt, String mirrorVersion, long files) {
    }

    record CheckResponse(String name, BigDecimal expected, BigDecimal actual, boolean passed, boolean hard, String detail) {
    }

    record FetchDetailResponse(FetchResponse fetch, ZipResponse zip, Map<String, Long> rows, List<CheckResponse> checks) {

        static FetchDetailResponse from(FetchQueryService.Detail d) {
            ZipResponse zip = d.zip().map(z -> new ZipResponse(z.bucket(), z.key(), z.fileType(), z.versionNo(), z.etag(),
                    z.sizeBytes(), z.lastModified(), z.manifestCreatedAt(), z.mirrorVersion(), z.files())).orElse(null);
            List<CheckResponse> checks = d.checks().stream().map(c -> new CheckResponse(c.name(), c.expected().orElse(null),
                    c.actual().orElse(null), c.passed(), c.hard(), c.detail())).toList();
            return new FetchDetailResponse(FetchResponse.from(d.request()), zip, new TreeMap<>(d.rows()), checks);
        }
    }
}
