package com.handsoffretail.verifone.parse.handlers;

import java.util.List;
import java.util.Set;

import com.handsoffretail.verifone.parse.UnmappedInputException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Ticket;

/** trExNetProds/trExNetProd: card-network product codes of the ticket. */
final class NetworkProductHandler implements ElementHandler<TicketDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("trExNetProds");
    }

    @Override
    public void handle(XmlElement e, TicketDraft d) {
        for (XmlElement child : e.allChildren()) {
            if (!child.name().equals("trExNetProd")) {
                throw new UnmappedInputException("element " + child.path());
            }
        }
        List<XmlElement> products = e.children("trExNetProd");
        for (int i = 0; i < products.size(); i++) {
            XmlElement p = products.get(i);
            d.networkProducts.add(new Ticket.NetworkProduct(i + 1, p.requiredChildText("trENPPcode"),
                    Values.intOf(p.requiredChild("trENPItemCnt")),
                    Values.requiredDecimal(p, "trENPAmount", Values.MONEY_SCALE)));
        }
    }
}
