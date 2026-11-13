package com.handsoffretail.verifone.verify;

import java.util.List;

/**
 * One verification of a just-loaded CPJ file, run inside the load transaction before commit. A new
 * verification is a new {@code LoadCheck} bean; {@link LoadVerifier} runs all of them
 * (IMPLEMENTATION_PLAN.md §4, open/closed). Returns at least one result; never throws for a failed
 * check (that is a result with {@code passed = false}).
 */
public interface LoadCheck {

    List<CheckResult> run(CheckContext context);
}
