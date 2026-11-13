package com.handsoffretail.gilbarco.verify;

import java.util.List;
import java.util.stream.Collectors;

/** A hard load check failed: the load is rolled back and the fetch becomes FAILED with this message. */
public class LoadCheckFailedException extends RuntimeException {

    private final transient List<CheckResult> failed;

    public LoadCheckFailedException(String what, List<CheckResult> failed) {
        super("load check failed for " + what + ": " + failed.stream()
                .map(r -> r.name() + " (expected " + r.expected().map(Object::toString).orElse("-") + ", actual "
                        + r.actual().map(Object::toString).orElse("-") + ")" + (r.detail().isBlank() ? "" : " " + r.detail()))
                .collect(Collectors.joining("; ")));
        this.failed = List.copyOf(failed);
    }

    public List<CheckResult> failed() {
        return failed;
    }
}
