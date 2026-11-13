package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Codes;
import com.handsoffretail.verifone.parse.model.Line;

/** trlFuel of a postFuel line: grade, price level, service mode, pump, volume, base price, deposit. */
final class LineFuelHandler implements ElementHandler<LineDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("trlFuel");
    }

    @Override
    public void handle(XmlElement e, LineDraft d) {
        XmlElement product = e.requiredChild("fuelProd");
        XmlElement volume = e.requiredChild("fuelVolume");
        d.fuel = new Line.Fuel(
                e.requiredAttr("type"),
                new Codes.FuelGrade(Values.requiredIntAttr(product, "sysid"), product.text().orElse(""),
                        product.attr("NAXMLFuelGradeID")),
                named(e.requiredChild("fuelMOP")),
                named(e.requiredChild("fuelSvcMode")),
                Values.intOf(e.requiredChild("fuelPosition")),
                Values.decimal(volume.requiredText(), Values.QUANTITY_SCALE, volume.path()),
                volume.requiredAttr("uom"),
                Values.requiredDecimal(e, "basePrice", Values.QUANTITY_SCALE),
                Values.optionalDecimal(e, "trlFuelDepst", Values.MONEY_SCALE),
                Values.optionalLong(e, "trlFuelSeq"));
    }

    private static Codes.NamedCode named(XmlElement e) {
        return new Codes.NamedCode(Values.requiredIntAttr(e, "sysid"), e.text().orElse(""));
    }
}
