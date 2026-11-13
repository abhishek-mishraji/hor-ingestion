package com.handsoffretail.verifone.verify;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Hard: no element, attribute or text of the file is unmapped. A file with one never gets here (the parser fails, naming its path); the row records that the file passed. */
@Component
@Order(20)
class UnmappedInputCheck implements LoadCheck {

    @Override
    public List<CheckResult> run(CheckContext c) {
        String skipped = c.facts().skippedJournal().isEmpty() ? "" : "; journal text not stored by design: " + c.facts().skippedJournal();
        return List.of(CheckResult.counted("no_unmapped_input", 0, 0, true,
                c.facts().transCount() + " trans read" + skipped));
    }
}
