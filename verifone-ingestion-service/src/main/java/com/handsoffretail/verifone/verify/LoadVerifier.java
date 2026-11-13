package com.handsoffretail.verifone.verify;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/** Runs every {@link LoadCheck} on a loaded file, records the results, and fails on a hard failure. */
@Component
public class LoadVerifier {

    private final List<LoadCheck> checks;
    private final LoadCheckRepository repository;

    public LoadVerifier(List<LoadCheck> checks, LoadCheckRepository repository) {
        this.checks = List.copyOf(checks);
        this.repository = repository;
    }

    /** @throws LoadCheckFailedException when a hard check failed (the caller's transaction rolls back) */
    public List<CheckResult> verify(CheckContext context) {
        List<CheckResult> results = new ArrayList<>();
        checks.forEach(check -> results.addAll(check.run(context)));
        repository.save(context.cpjFileId(), results);
        List<CheckResult> failed = results.stream().filter(CheckResult::failedHard).toList();
        if (!failed.isEmpty()) {
            throw new LoadCheckFailedException(context.entry().path(), failed);
        }
        return results;
    }
}
