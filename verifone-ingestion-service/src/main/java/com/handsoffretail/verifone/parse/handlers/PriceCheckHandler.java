package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.CashierEvent;
import com.handsoffretail.verifone.parse.model.Codes;

/** priceCheckCancelItem of a PRICE CHECK event: the item looked up (no department in the file). */
final class PriceCheckHandler implements ElementHandler<EventDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("priceCheckCancelItem");
    }

    @Override
    public void handle(XmlElement e, EventDraft d) {
        d.priceCheck = new CashierEvent.PriceCheck(
                new Codes.Item(e.requiredChildText("trlUPC"), e.requiredChildText("trlModifier"),
                        e.requiredChildText("trlDesc")),
                Values.requiredDecimal(e, "trlQty", Values.QUANTITY_SCALE),
                Values.requiredDecimal(e, "trlUnitPrice", Values.QUANTITY_SCALE),
                Values.requiredDecimal(e, "trlLineTot", Values.MONEY_SCALE));
    }
}
