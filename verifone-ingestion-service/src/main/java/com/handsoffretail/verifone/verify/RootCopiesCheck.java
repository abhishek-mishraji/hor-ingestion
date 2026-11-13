package com.handsoffretail.verifone.verify;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Hard: the root-level openCashier / closeCashier records equal their trans copies. */
@Component
@Order(40)
class RootCopiesCheck implements LoadCheck {

    @Override
    public List<CheckResult> run(CheckContext c) {
        return List.of(CheckResult.counted("root_cashier_copies_equal", c.facts().rootCopies(), c.facts().rootCopiesEqual(),
                true, String.join("; ", c.facts().rootCopyProblems())));
    }
}
