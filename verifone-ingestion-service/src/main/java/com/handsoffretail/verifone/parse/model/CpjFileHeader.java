package com.handsoffretail.verifone.parse.model;

import java.time.OffsetDateTime;
import java.util.Objects;

/** The day: {@code <transSet>} attributes, open/close times and the start counters. */
public record CpjFileHeader(int periodId, String periodName, OffsetDateTime periodLongId, String siteId, int daySeq,
        OffsetDateTime openedAt, OffsetDateTime closedAt, FileTotals startTotals) {

    public CpjFileHeader {
        Objects.requireNonNull(periodName, "periodName");
        Objects.requireNonNull(periodLongId, "periodLongId");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(openedAt, "openedAt");
        Objects.requireNonNull(closedAt, "closedAt");
        Objects.requireNonNull(startTotals, "startTotals");
    }
}
