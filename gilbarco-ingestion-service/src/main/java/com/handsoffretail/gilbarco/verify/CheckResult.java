package com.handsoffretail.gilbarco.verify;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * One row of {@code ingest.load_checks}. A failed hard check rolls the load back; a failed soft check is
 * recorded and shown, and the load stays.
 */
public record CheckResult(String name, Optional<BigDecimal> expected, Optional<BigDecimal> actual, boolean passed,
        boolean hard, String detail) {

    public CheckResult {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(detail, "detail");
    }

    public static CheckResult counted(String name, long expected, long actual, boolean hard, String detail) {
        return new CheckResult(name, Optional.of(BigDecimal.valueOf(expected)), Optional.of(BigDecimal.valueOf(actual)),
                expected == actual, hard, detail);
    }

    public static CheckResult amounts(String name, BigDecimal expected, BigDecimal actual, boolean hard, String detail) {
        return new CheckResult(name, Optional.ofNullable(expected), Optional.ofNullable(actual),
                expected != null && actual != null && expected.compareTo(actual) == 0, hard, detail);
    }

    public boolean failedHard() {
        return hard && !passed;
    }
}
