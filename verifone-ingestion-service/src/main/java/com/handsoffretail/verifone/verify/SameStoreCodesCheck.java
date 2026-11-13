package com.handsoffretail.verifone.verify;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Hard: no row of the file points at another store's codes. */
@Component
@Order(60)
class SameStoreCodesCheck implements LoadCheck {

    private final LoadCheckRepository repository;

    SameStoreCodesCheck(LoadCheckRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<CheckResult> run(CheckContext c) {
        return List.of(CheckResult.counted("codes_of_same_store", 0, repository.foreignCodeReferences(c.cpjFileId(), c.storeId()),
                true, "references to another store's registers, cashiers, departments, items, taxes, payment types, cards, fuel, promotions, loyalty"));
    }
}
