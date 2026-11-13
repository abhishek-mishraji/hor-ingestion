package com.handsoffretail.verifone.parse.handlers;

import java.util.List;
import java.util.Set;

import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Codes;
import com.handsoffretail.verifone.parse.model.Line;

/** trlMixMatches/trlMatchLine: mix-and-match promotions applied to the line. */
final class LinePromotionHandler implements ElementHandler<LineDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("trlMixMatches");
    }

    @Override
    public void handle(XmlElement e, LineDraft d) {
        List<XmlElement> matches = e.children("trlMatchLine");
        for (int i = 0; i < matches.size(); i++) {
            XmlElement m = matches.get(i);
            XmlElement id = m.requiredChild("trlPromotionID");
            Codes.Promotion promotion = new Codes.Promotion(id.requiredText(), m.childText("trlMatchName").orElse(""),
                    id.requiredAttr("promotype"));
            d.promotions.add(new Line.Promotion(i + 1, promotion, Values.intOf(m.requiredChild("trlMatchMixes")),
                    Values.requiredDecimal(m, "trlMatchQuantity", Values.QUANTITY_SCALE),
                    Values.requiredDecimal(m, "trlMatchPrice", Values.MONEY_SCALE),
                    Values.requiredDecimal(m, "trlPromoAmount", Values.MONEY_SCALE)));
        }
    }
}
