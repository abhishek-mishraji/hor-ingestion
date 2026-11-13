package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Ticket;

/** Ticket identity: trTickNum, trOriginalTickNum, trRecall, trUniqueSN, physicalRegisterID, till. */
final class TicketNumberHandler implements ElementHandler<HeaderDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("trTickNum", "trOriginalTickNum", "trRecall", "trUniqueSN", "physicalRegisterID", "till");
    }

    @Override
    public void handle(XmlElement e, HeaderDraft draft) {
        switch (e.name()) {
            case "trTickNum" -> {
                draft.ticketPosNum = Values.intOf(e.requiredChild("posNum"));
                draft.trSeq = Values.longValue(e.requiredChildText("trSeq"), e.path() + "/trSeq");
            }
            case "trOriginalTickNum" -> draft.original = new Ticket.OriginalTicket(
                    Values.intOf(e.requiredChild("posNum")),
                    Values.longValue(e.requiredChildText("trSeq"), e.path() + "/trSeq"));
            case "trRecall" -> draft.recall = new Ticket.Recall(e.requiredAttr("transType"),
                    Values.intOf(e.requiredChild("posNum")),
                    Values.longValue(e.requiredChildText("trSeq"), e.path() + "/trSeq"));
            case "trUniqueSN" -> draft.uniqueSn = e.text().map(t -> Values.longValue(t, e.path())).orElse(null);
            case "physicalRegisterID" -> draft.physicalRegisterId = e.text().map(t -> Values.integer(t, e.path())).orElse(null);
            default -> draft.till = e.text().map(t -> Values.integer(t, e.path())).orElse(null);
        }
    }
}
