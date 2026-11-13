package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Ticket;

/** trValue/trFstmp: the food stamp (EBT) part of a ticket. */
final class FoodStampHandler implements ElementHandler<ValueDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("trFstmp");
    }

    @Override
    public void handle(XmlElement e, ValueDraft d) {
        d.foodStamp = new Ticket.FoodStamp(
                Values.requiredDecimal(e, "trFstmpTot", Values.MONEY_SCALE),
                Values.requiredDecimal(e, "trFstmpTax", Values.MONEY_SCALE),
                Values.requiredDecimal(e, "trFstmpChg", Values.MONEY_SCALE),
                Values.requiredDecimal(e, "trFstmpTnd", Values.MONEY_SCALE));
    }
}
