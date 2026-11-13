package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.XmlElement;

/** trHeader priceChangedBy / priceChangeOrigin of a fuel price change. */
final class PriceChangeHeaderHandler implements ElementHandler<HeaderDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("priceChangedBy", "priceChangeOrigin");
    }

    @Override
    public void handle(XmlElement e, HeaderDraft draft) {
        if (e.name().equals("priceChangedBy")) {
            draft.priceChangedBy = e.text().orElse(null);
        } else {
            draft.priceChangeOrigin = e.text().orElse(null);
        }
    }
}
