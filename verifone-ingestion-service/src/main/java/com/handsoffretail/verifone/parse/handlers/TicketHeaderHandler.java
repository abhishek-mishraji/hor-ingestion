package com.handsoffretail.verifone.parse.handlers;

import java.util.List;
import java.util.Set;

import com.handsoffretail.verifone.parse.XmlElement;

/** trHeader of a ticket, through the ticket header handlers. */
final class TicketHeaderHandler implements ElementHandler<TicketDraft> {

    private final HandlerRegistry<HeaderDraft> header = new HandlerRegistry<>(List.of(new HeaderMomentHandler(),
            new HeaderTerminalHandler(), new CashierHandler(), new OriginalCashierHandler(), new TicketNumberHandler(),
            new TicketMiscHandler(), new PriceChangeHeaderHandler()));

    @Override
    public Set<String> elements() {
        return Set.of("trHeader");
    }

    @Override
    public void handle(XmlElement e, TicketDraft d) {
        header.dispatchChildren(e, d.header);
    }
}
