package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.UnmappedInputException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Ticket;

/** trLoyalty/trLoyaltyProgram: one entry per loyalty program (up to 2 per ticket in the sample). */
final class TicketLoyaltyHandler implements ElementHandler<TicketDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("trLoyalty");
    }

    @Override
    public void handle(XmlElement e, TicketDraft d) {
        for (XmlElement child : e.allChildren()) {
            if (!child.name().equals("trLoyaltyProgram")) {
                throw new UnmappedInputException("element " + child.path());
            }
        }
        for (XmlElement p : e.children("trLoyaltyProgram")) {
            String program = p.requiredAttr("programID");
            if (d.loyalty.stream().anyMatch(l -> l.programCode().equals(program))) {
                throw new CpjFormatException("loyalty program " + program + " twice at " + p.path());
            }
            d.loyalty.add(new Ticket.Loyalty(program, p.childText("trloAccount"), p.childText("trloAuthReply"),
                    Values.optionalDecimal(p, "trloAutoDisc", Values.MONEY_SCALE),
                    Values.optionalDecimal(p, "trloCustDisc", Values.MONEY_SCALE), p.childText("trloEntryMeth"),
                    Values.optionalDecimal(p, "trloSubTotal", Values.MONEY_SCALE)));
        }
    }
}
