package com.handsoffretail.gilbarco.verify;

import java.util.List;

/**
 * One verification of a just-loaded day, run inside the load transaction before commit. Checks that hold for
 * every file type implement {@link CommonLoadCheck}; a file type contributes its own through
 * {@code FileTypeProcessor.supportedChecks()} (open/closed). Returns at least one result; never throws for a
 * failed check (that is a result with {@code passed = false}).
 */
public interface LoadCheck {

    List<CheckResult> run(CheckContext context);

    /** A check that applies to every file type; {@link LoadVerifier} runs all of these beans. */
    interface CommonLoadCheck extends LoadCheck {
    }
}
