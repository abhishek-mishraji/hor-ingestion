package com.handsoffretail.gilbarco.verify;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Runs the common checks and the file type's own checks on a loaded day, records the results in
 * ingest.load_checks, and fails on a hard failure (the caller's transaction then rolls back).
 */
@Component
public class LoadVerifier {

    private final List<LoadCheck.CommonLoadCheck> commonChecks;
    private final LoadCheckRepository repository;

    public LoadVerifier(List<LoadCheck.CommonLoadCheck> commonChecks, LoadCheckRepository repository) {
        this.commonChecks = List.copyOf(commonChecks);
        this.repository = repository;
    }

    /** @throws LoadCheckFailedException when a hard check failed */
    public List<CheckResult> verify(CheckContext context, List<? extends LoadCheck> fileTypeChecks) {
        List<CheckResult> results = new ArrayList<>();
        commonChecks.forEach(check -> results.addAll(check.run(context)));
        fileTypeChecks.forEach(check -> results.addAll(check.run(context)));
        repository.save(context.fetchRequestId(), results);
        List<CheckResult> failed = results.stream().filter(CheckResult::failedHard).toList();
        if (!failed.isEmpty()) {
            throw new LoadCheckFailedException(context.fileType() + " " + context.businessDate(), failed);
        }
        return results;
    }
}
