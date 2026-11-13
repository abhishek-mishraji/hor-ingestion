package com.handsoffretail.verifone.parse.model;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/** trans type="periodClose": a shift (1) or day (2) close. */
public record PeriodClose(int periodType, int periodSeq, String periodName, OffsetDateTime closedAt, String uniqueId,
        Optional<Boolean> mwsEnabled) {

    public PeriodClose {
        Objects.requireNonNull(periodName, "periodName");
        Objects.requireNonNull(closedAt, "closedAt");
        Objects.requireNonNull(uniqueId, "uniqueId");
        Objects.requireNonNull(mwsEnabled, "mwsEnabled");
    }
}
