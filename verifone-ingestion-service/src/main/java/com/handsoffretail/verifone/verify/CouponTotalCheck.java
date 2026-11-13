package com.handsoffretail.verifone.verify;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Soft: each ticket's couponDiscTotal equals the sum of its coupons, and the coupons stored equal the file's. */
@Component
@Order(90)
class CouponTotalCheck implements LoadCheck {

    private final LoadCheckRepository repository;

    CouponTotalCheck(LoadCheckRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<CheckResult> run(CheckContext c) {
        BigDecimal stored = repository.storedCouponSum(c.cpjFileId());
        CheckResult result = CheckResult.amounts("coupon_total", c.facts().couponTotalInFile(), stored, false,
                c.facts().ticketsWithCouponMismatch() + " ticket(s) whose couponDiscTotal differs from their coupons");
        boolean passed = result.passed() && c.facts().ticketsWithCouponMismatch() == 0 && c.facts().couponSum().compareTo(stored) == 0;
        return List.of(new CheckResult(result.name(), result.expected(), result.actual(), passed, false, result.detail()));
    }
}
