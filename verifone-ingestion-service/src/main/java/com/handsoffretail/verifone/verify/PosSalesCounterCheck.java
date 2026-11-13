package com.handsoffretail.verifone.verify;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Soft: the change of the POS's never-resetting sales counter equals the total of the tickets that count as sales (PROVEN rule, IMPLEMENTATION_PLAN.md Phase 4). */
@Component
@Order(80)
class PosSalesCounterCheck implements LoadCheck {

    private final LoadCheckRepository repository;

    PosSalesCounterCheck(LoadCheckRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<CheckResult> run(CheckContext c) {
        return List.of(CheckResult.amounts("pos_sales_counter", repository.counterChange(c.cpjFileId()),
                repository.countedSalesTotal(c.cpjFileId()), false,
                "endTotals - startTotals overallSales vs sum of sale / network sale / refund sale totals, not suspended, prepay or rollback"));
    }
}
