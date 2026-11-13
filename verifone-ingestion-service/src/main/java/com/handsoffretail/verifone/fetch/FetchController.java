package com.handsoffretail.verifone.fetch;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import com.handsoffretail.verifone.common.PageResult;
import com.handsoffretail.verifone.common.Paging;
import com.handsoffretail.verifone.stores.StoreDirectory;

@RestController
@RequestMapping("/api/ingestion/verifone/fetches")
class FetchController {

    private final FetchService fetches;
    private final FetchQueryService queries;
    private final StoreDirectory stores;

    FetchController(FetchService fetches, FetchQueryService queries, StoreDirectory stores) {
        this.fetches = fetches;
        this.queries = queries;
        this.stores = stores;
    }

    @PostMapping
    ResponseEntity<Map<String, Long>> start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StartFetchRequest request) {
        HorPrincipal caller = HorPrincipal.from(jwt);
        stores.require(caller, request.storeId());
        long id = fetches.start(request.storeId(), request.businessDate(), request.mode(), caller.id());
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
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        HorPrincipal caller = HorPrincipal.from(jwt);
        if (storeId != null) {
            stores.require(caller, storeId);
        } else if (!caller.allStores()) {
            // Only platform admins today; a store-scoped caller must name a store (later phase: filter instead).
            throw new DomainException(CommonErrorCode.FORBIDDEN, "storeId is required");
        }
        return queries.history(Optional.ofNullable(storeId), Optional.ofNullable(from), Optional.ofNullable(to),
                Paging.of(limit, offset)).map(FetchResponse::from);
    }

    record StartFetchRequest(@NotNull @Positive Long storeId, @NotNull LocalDate businessDate,
            @NotNull FetchRequest.Mode mode) {
    }

    record FetchResponse(long id, long storeId, LocalDate businessDate, FetchRequest.Mode mode, FetchRequest.Status status,
            String message, long requestedBy, Instant requestedAt, Instant finishedAt, Long durationMs) {

        static FetchResponse from(FetchRequest r) {
            return new FetchResponse(r.fetchRequestId(), r.storeId(), r.businessDate(), r.mode(), r.status(),
                    r.message().orElse(null), r.requestedBy(), r.requestedAt(), r.finishedAt().orElse(null),
                    r.finishedAt().map(f -> Duration.between(r.requestedAt(), f).toMillis()).orElse(null));
        }
    }

    record ZipResponse(String bucket, String key, int version, String etag, long sizeBytes, Instant lastModified,
            Instant manifestCreatedAt, String mirrorVersion) {
    }

    record CheckResponse(String name, BigDecimal expected, BigDecimal actual, boolean passed, boolean hard, String detail) {
    }

    record FileResponse(long cpjFileId, String filePath, int daySeq, Instant loadedAt, Map<String, Long> rows,
            List<CheckResponse> checks) {
    }

    record FetchDetailResponse(FetchResponse fetch, ZipResponse zip, List<FileResponse> files) {

        static FetchDetailResponse from(FetchQueryService.Detail d) {
            ZipResponse zip = d.zip().map(z -> new ZipResponse(z.bucket(), z.key(), z.versionNo(), z.etag(), z.sizeBytes(),
                    z.lastModified(), z.manifestCreatedAt(), z.mirrorVersion())).orElse(null);
            List<FileResponse> files = d.files().stream().map(f -> new FileResponse(f.cpjFileId(), f.filePath(), f.daySeq(),
                    f.loadedAt(), f.rows(), f.checks().stream().map(c -> new CheckResponse(c.name(), c.expected().orElse(null),
                            c.actual().orElse(null), c.passed(), c.hard(), c.detail())).toList())).toList();
            return new FetchDetailResponse(FetchResponse.from(d.request()), zip, files);
        }
    }
}
