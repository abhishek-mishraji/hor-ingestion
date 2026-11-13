package com.handsoffretail.verifone.parse.handlers;

import java.math.BigDecimal;
import java.util.Set;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Codes;
import com.handsoffretail.verifone.parse.model.Line;

/** The line itself: department, category, item, quantities, prices, void index, override, discount. */
final class LineCoreHandler implements ElementHandler<LineDraft> {

    static final Set<String> DEPARTMENT_TYPES = Set.of("norm", "neg", "fuel");
    static final Set<String> UPC_ENTRY_METHODS = Set.of("scanned", "key", "manual");

    @Override
    public Set<String> elements() {
        return Set.of("trlDept", "trlCat", "trlNetwCode", "trlQty", "trlSign", "trlSellUnit", "trlUnitPrice",
                "trlLineTot", "trlDesc", "trlUPC", "trlModifier", "trlUPCEntry", "trlVoidLineIx", "trlPrcOvrd", "trlDisc");
    }

    @Override
    public void handle(XmlElement e, LineDraft d) {
        switch (e.name()) {
            case "trlDept" -> d.department = department(e);
            case "trlCat" -> d.category = new Codes.Category(Values.requiredIntAttr(e, "number"), e.text().orElse(""));
            case "trlNetwCode" -> d.networkCode = e.text().orElse("");
            case "trlQty" -> d.quantity = Values.decimal(e.requiredText(), Values.QUANTITY_SCALE, e.path());
            case "trlSign" -> d.sign = sign(e);
            case "trlSellUnit" -> d.sellUnit = Values.decimal(e.requiredText(), Values.QUANTITY_SCALE, e.path());
            case "trlUnitPrice" -> d.unitPrice = Values.decimal(e.requiredText(), Values.QUANTITY_SCALE, e.path());
            case "trlLineTot" -> d.lineTotal = Values.decimal(e.requiredText(), Values.MONEY_SCALE, e.path());
            case "trlDesc" -> d.description = e.text().orElse(null);
            case "trlUPC" -> d.upc = e.requiredText();
            case "trlModifier" -> d.modifier = e.requiredText();
            case "trlUPCEntry" -> d.upcEntryMethod = upcEntry(e);
            case "trlVoidLineIx" -> d.voidedLineNo = Values.intOf(e);
            case "trlPrcOvrd" -> d.priceOverride = Values.decimal(e.requiredText(), Values.QUANTITY_SCALE, e.path());
            default -> d.discount = new Line.Discount(e.requiredAttr("type"),
                    Values.decimal(e.requiredText(), Values.MONEY_SCALE, e.path()));
        }
    }

    private static Codes.Department department(XmlElement e) {
        String type = e.requiredAttr("type");
        if (!DEPARTMENT_TYPES.contains(type)) {
            throw new CpjFormatException("unknown department type '" + type + "' at " + e.path());
        }
        return new Codes.Department(Values.requiredIntAttr(e, "number"), e.text().orElse(""), type);
    }

    private static int sign(XmlElement e) {
        BigDecimal sign = Values.decimal(e.requiredText(), Values.MONEY_SCALE, e.path());
        if (sign.compareTo(BigDecimal.ONE) == 0) {
            return 1;
        }
        if (sign.compareTo(BigDecimal.ONE.negate()) == 0) {
            return -1;
        }
        throw new CpjFormatException("trlSign must be 1 or -1 at " + e.path());
    }

    private static String upcEntry(XmlElement e) {
        String type = e.requiredAttr("type");
        if (!UPC_ENTRY_METHODS.contains(type)) {
            throw new CpjFormatException("unknown UPC entry type '" + type + "' at " + e.path());
        }
        return type;
    }
}
