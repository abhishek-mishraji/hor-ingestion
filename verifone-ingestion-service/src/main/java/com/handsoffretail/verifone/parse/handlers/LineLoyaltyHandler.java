package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Line;

/**
 * Loyalty discounts on a line: trloPPGDisc (cents per gallon on fuel) and trloLnItemDisc (items).
 * Both may occur once per loyalty program (two trloPPGDisc seen on 6 sample fuel lines, V5).
 */
final class LineLoyaltyHandler implements ElementHandler<LineDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("trloPPGDisc", "trloLnItemDisc");
    }

    @Override
    public Set<String> repeatable() {
        return elements();
    }

    @Override
    public void handle(XmlElement e, LineDraft d) {
        String program = e.requiredAttr("programID");
        if (e.name().equals("trloPPGDisc")) {
            if (d.fuelDiscounts.stream().anyMatch(x -> x.programCode().equals(program))) {
                throw new CpjFormatException("two trloPPGDisc of program " + program + " at " + e.path());
            }
            d.fuelDiscounts.add(new Line.FuelDiscount(program,
                    Values.requiredDecimal(e, "PPGGalDisc", Values.QUANTITY_SCALE),
                    Values.requiredDecimal(e, "PPGOrgPrice", Values.QUANTITY_SCALE),
                    Values.requiredDecimal(e, "PPGTotDisc", Values.MONEY_SCALE),
                    Values.requiredDecimal(e, "PPGVol", Values.QUANTITY_SCALE)));
        } else {
            if (d.loyaltyDiscounts.stream().anyMatch(x -> x.programCode().equals(program))) {
                throw new CpjFormatException("two trloLnItemDisc of program " + program + " at " + e.path());
            }
            d.loyaltyDiscounts.add(new Line.LoyaltyDiscount(program,
                    Values.requiredDecimal(e, "discAmt", Values.MONEY_SCALE),
                    Values.requiredDecimal(e, "qty", Values.QUANTITY_SCALE),
                    Values.requiredDecimal(e, "taxCred", Values.MONEY_SCALE)));
        }
    }
}
