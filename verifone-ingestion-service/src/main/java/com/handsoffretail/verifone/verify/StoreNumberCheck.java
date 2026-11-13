package com.handsoffretail.verifone.verify;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Hard: every trHeader/storeNumber equals transSet/@site. */
@Component
@Order(30)
class StoreNumberCheck implements LoadCheck {

    @Override
    public List<CheckResult> run(CheckContext c) {
        int checked = c.facts().storeNumbersChecked();
        int mismatches = c.facts().storeNumberMismatchCount();
        return List.of(CheckResult.counted("store_number_equals_site", checked, checked - mismatches, true,
                "examples: " + String.join("; ", c.facts().storeNumberMismatches())));
    }
}
