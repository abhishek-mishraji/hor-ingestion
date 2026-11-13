package com.handsoffretail.verifone.parse.handlers;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.UnmappedInputException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Codes;
import com.handsoffretail.verifone.parse.model.Line;

/** trlTaxes: one trlTax and one trlRate per tax @sysid, with the same @cat name. */
final class LineTaxHandler implements ElementHandler<LineDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("trlTaxes");
    }

    @Override
    public void handle(XmlElement e, LineDraft d) {
        Map<Integer, Pair> bySysid = new LinkedHashMap<>();
        for (XmlElement part : e.allChildren()) {
            part.markUsed();
            int sysid = Values.requiredIntAttr(part, "sysid");
            Pair pair = bySysid.computeIfAbsent(sysid, s -> new Pair());
            String name = part.requiredAttr("cat");
            BigDecimal value = Values.decimal(part.requiredText(), Values.QUANTITY_SCALE, part.path());
            switch (part.name()) {
                case "trlTax" -> {
                    pair.tax = once(pair.tax, value, part);
                    pair.reverse = Values.flag01(part.requiredAttr("reverse"), part.path() + "/@reverse");
                    pair.name = sameName(pair.name, name, part);
                }
                case "trlRate" -> {
                    pair.rate = once(pair.rate, value, part);
                    pair.name = sameName(pair.name, name, part);
                }
                default -> throw new UnmappedInputException("element " + part.path());
            }
        }
        bySysid.forEach((sysid, pair) -> {
            if (pair.tax == null || pair.rate == null) {
                throw new CpjFormatException("tax " + sysid + " needs both trlTax and trlRate at " + e.path());
            }
            if (pair.tax.stripTrailingZeros().scale() > Values.MONEY_SCALE) {
                throw new CpjFormatException("more than 2 decimal places in trlTax at " + e.path());
            }
            d.taxes.add(new Line.Tax(new Codes.Tax(sysid, pair.name), pair.tax, pair.rate, pair.reverse));
        });
    }

    private static BigDecimal once(BigDecimal current, BigDecimal value, XmlElement part) {
        if (current != null) {
            throw new CpjFormatException(part.path() + " repeated for the same tax");
        }
        return value;
    }

    private static String sameName(String current, String name, XmlElement part) {
        if (current != null && !current.equals(name)) {
            throw new CpjFormatException("one tax with two @cat names at " + part.path());
        }
        return name;
    }

    private static final class Pair {
        BigDecimal tax;
        BigDecimal rate;
        boolean reverse;
        String name;
    }
}
