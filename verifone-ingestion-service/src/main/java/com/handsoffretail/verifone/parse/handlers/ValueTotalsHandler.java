package com.handsoffretail.verifone.parse.handlers;

import java.math.BigDecimal;
import java.util.Set;

import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;

/** trValue totals: trCurrTot (@locale), trTotNoTax, trTotTax, trTotWTax, trSTotalizer, trGTotalizer, recallAmt. */
final class ValueTotalsHandler implements ElementHandler<ValueDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("trCurrTot", "trTotNoTax", "trTotTax", "trTotWTax", "trSTotalizer", "trGTotalizer", "recallAmt");
    }

    @Override
    public void handle(XmlElement e, ValueDraft d) {
        BigDecimal amount = Values.decimal(e.requiredText(), Values.MONEY_SCALE, e.path());
        switch (e.name()) {
            case "trCurrTot" -> {
                d.total = amount;
                d.currency = e.requiredAttr("locale");
            }
            case "trTotNoTax" -> d.totalNoTax = amount;
            case "trTotTax" -> d.totalTax = amount;
            case "trTotWTax" -> d.totalWithTax = amount;
            case "trSTotalizer" -> d.sTotalizer = amount;
            case "trGTotalizer" -> d.gTotalizer = amount;
            default -> d.recallAmount = amount;
        }
    }
}
