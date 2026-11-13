package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.UnmappedInputException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.CashierEvent;

/** trjDrTotals/trjdrTotal of a login/logout: drawer amount per tender @name. */
final class DrawerTotalsHandler implements ElementHandler<EventDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("trjDrTotals");
    }

    @Override
    public void handle(XmlElement e, EventDraft d) {
        for (XmlElement total : e.allChildren()) {
            if (!total.name().equals("trjdrTotal")) {
                throw new UnmappedInputException("element " + total.path());
            }
            total.markUsed();
            String tender = total.requiredAttr("name");
            if (d.drawerTotals.stream().anyMatch(t -> t.tenderName().equals(tender))) {
                throw new CpjFormatException("tender " + tender + " twice at " + total.path());
            }
            d.drawerTotals.add(new CashierEvent.DrawerTotal(tender,
                    Values.decimal(total.requiredText(), Values.MONEY_SCALE, total.path())));
        }
    }
}
