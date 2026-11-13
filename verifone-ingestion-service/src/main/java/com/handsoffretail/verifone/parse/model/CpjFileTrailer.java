package com.handsoffretail.verifone.parse.model;

import java.util.List;
import java.util.Objects;

/** What is only complete at the end of the file: end counters, cashier sessions, period closes. */
public record CpjFileTrailer(FileTotals endTotals, List<CashierSession> sessions, List<PeriodClose> periodCloses) {

    public CpjFileTrailer {
        Objects.requireNonNull(endTotals, "endTotals");
        sessions = List.copyOf(sessions);
        periodCloses = List.copyOf(periodCloses);
    }
}
