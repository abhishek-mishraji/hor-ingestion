package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Codes;

/** trMiscRefNum (payout name / safe drop number), trFuelOnlyCst (empty marker), trHouseAcct. */
final class TicketMiscHandler implements ElementHandler<HeaderDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("trMiscRefNum", "trFuelOnlyCst", "trHouseAcct");
    }

    @Override
    public void handle(XmlElement e, HeaderDraft draft) {
        switch (e.name()) {
            case "trMiscRefNum" -> draft.miscRef = e.text().orElse(null);
            case "trFuelOnlyCst" -> {
                e.requireEmpty();
                draft.fuelOnlyCustomer = true;
            }
            default -> draft.houseAccount = new Codes.HouseAccount(e.requiredText(), e.requiredAttr("name"));
        }
    }
}
