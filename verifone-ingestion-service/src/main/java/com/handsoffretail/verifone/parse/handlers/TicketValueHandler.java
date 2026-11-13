package com.handsoffretail.verifone.parse.handlers;

import java.util.List;
import java.util.Set;

import com.handsoffretail.verifone.parse.XmlElement;

/** trValue, through the value handlers (totals, taxes, food stamps, age check). */
final class TicketValueHandler implements ElementHandler<TicketDraft> {

    private final HandlerRegistry<ValueDraft> value = new HandlerRegistry<>(List.of(new ValueTotalsHandler(),
            new TicketTaxHandler(), new FoodStampHandler(), new AgeCheckHandler()));

    @Override
    public Set<String> elements() {
        return Set.of("trValue");
    }

    @Override
    public void handle(XmlElement e, TicketDraft d) {
        d.value = new ValueDraft();
        value.dispatchChildren(e, d.value);
    }
}
