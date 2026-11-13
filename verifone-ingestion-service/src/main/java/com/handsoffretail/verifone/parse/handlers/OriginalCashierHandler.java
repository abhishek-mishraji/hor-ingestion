package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.XmlElement;

/** trHeader/originalCashier (prepay completions): the cashier who took the prepay. */
final class OriginalCashierHandler implements ElementHandler<HeaderDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("originalCashier");
    }

    @Override
    public void handle(XmlElement e, HeaderDraft draft) {
        draft.cashiers.add(CashierHandler.cashier(e, CashierHandler.ROLE_ORIGINAL));
    }
}
