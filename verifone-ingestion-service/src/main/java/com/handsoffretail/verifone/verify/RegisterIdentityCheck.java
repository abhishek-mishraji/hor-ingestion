package com.handsoffretail.verifone.verify;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Hard: one posNum never has two physicalRegisterIDs, in the file or against the stored register. */
@Component
@Order(50)
class RegisterIdentityCheck implements LoadCheck {

    @Override
    public List<CheckResult> run(CheckContext c) {
        List<String> conflicts = new ArrayList<>(c.facts().registerConflicts());
        conflicts.addAll(c.storedRegisterConflicts());
        return List.of(CheckResult.counted("register_physical_id_consistent", 0, conflicts.size(), true,
                c.facts().physicalRegisterIds().size() + " register(s) with a physical id; " + String.join("; ", conflicts)));
    }
}
