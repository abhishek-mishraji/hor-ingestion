package com.handsoffretail.verifone.parse.model;

import java.math.BigDecimal;
import java.util.Objects;

/** startTotals / endTotals: the POS's never-resetting sales counters at open and close. */
public record FileTotals(Kind kind, BigDecimal insideSales, BigDecimal insideGrand, BigDecimal outsideSales,
        BigDecimal outsideGrand, BigDecimal attendantInsideSales, BigDecimal attendantInsideGrand,
        BigDecimal attendantOutsideSales, BigDecimal attendantOutsideGrand, BigDecimal overallSales,
        BigDecimal overallGrand) {

    public FileTotals {
        Objects.requireNonNull(kind, "kind");
    }

    public enum Kind {
        START,
        END
    }
}
