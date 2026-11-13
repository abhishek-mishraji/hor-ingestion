package com.handsoffretail.verifone.parse.handlers;

import java.util.List;
import java.util.Set;

import com.handsoffretail.verifone.parse.UnmappedInputException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Ticket;

/** trPriceChange/priceChangeInfo of a fuelpriceChange trans (sysids and prices only). */
final class PriceChangeHandler implements ElementHandler<TicketDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("trPriceChange");
    }

    @Override
    public void handle(XmlElement e, TicketDraft d) {
        d.hasPriceChange = true;
        for (XmlElement child : e.allChildren()) {
            if (!child.name().equals("priceChangeInfo")) {
                throw new UnmappedInputException("element " + child.path());
            }
        }
        List<XmlElement> infos = e.children("priceChangeInfo");
        for (int i = 0; i < infos.size(); i++) {
            XmlElement x = infos.get(i);
            XmlElement previous = x.requiredChild("previousPrice");
            XmlElement current = x.requiredChild("currentPrice");
            d.priceChangeEntries.add(new Ticket.PriceChangeEntry(i + 1, Values.optionalIntAttr(x, "sysid"),
                    sysid(x, "fuelProd"), sysid(x, "fuelSvcMode"), sysid(x, "fuelMOP"), sysid(x, "fuelTier"),
                    Values.decimal(previous.requiredText(), Values.QUANTITY_SCALE, previous.path()),
                    Values.optionalIntAttr(previous, "sysid"),
                    Values.decimal(current.requiredText(), Values.QUANTITY_SCALE, current.path()),
                    Values.optionalIntAttr(current, "sysid")));
        }
    }

    private static int sysid(XmlElement info, String child) {
        XmlElement e = info.requiredChild(child);
        int sysid = Values.requiredIntAttr(e, "sysid");
        e.text().ifPresent(t -> {
            throw new UnmappedInputException("text in " + e.path());
        });
        return sysid;
    }
}
