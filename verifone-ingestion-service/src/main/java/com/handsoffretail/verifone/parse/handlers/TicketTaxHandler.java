package com.handsoffretail.verifone.parse.handlers;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.UnmappedInputException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Codes;
import com.handsoffretail.verifone.parse.model.Ticket;

/**
 * trValue/trTax: taxAmts (sale) and negTaxAmts (tax handed back on a refund), each holding per tax
 * @sysid a taxAmt, taxRate, taxNet, taxAttribute and optionally taxExempt / taxExemptAgainst.
 */
final class TicketTaxHandler implements ElementHandler<ValueDraft> {

    private static final Map<String, Boolean> BLOCKS = Map.of("taxAmts", false, "negTaxAmts", true);
    private static final Set<String> PARTS = Set.of("taxAmt", "taxRate", "taxNet", "taxAttribute", "taxExempt",
            "taxExemptAgainst");

    @Override
    public Set<String> elements() {
        return Set.of("trTax");
    }

    @Override
    public void handle(XmlElement e, ValueDraft d) {
        for (XmlElement block : e.allChildren()) {
            Boolean refund = BLOCKS.get(block.name());
            if (refund == null) {
                throw new UnmappedInputException("element " + block.path());
            }
            block.markUsed();
            perSysid(block).forEach((sysid, parts) -> d.taxes.add(tax(sysid, parts, refund, block)));
        }
    }

    private static Map<Integer, Map<String, XmlElement>> perSysid(XmlElement block) {
        Map<Integer, Map<String, XmlElement>> bySysid = new LinkedHashMap<>();
        for (XmlElement part : block.allChildren()) {
            if (!PARTS.contains(part.name())) {
                throw new UnmappedInputException("element " + part.path());
            }
            part.markUsed();
            Map<String, XmlElement> parts = bySysid.computeIfAbsent(Values.requiredIntAttr(part, "sysid"),
                    s -> new LinkedHashMap<>());
            if (parts.put(part.name(), part) != null) {
                throw new CpjFormatException(part.path() + " repeated for the same tax");
            }
        }
        return bySysid;
    }

    private static Ticket.Tax tax(int sysid, Map<String, XmlElement> parts, boolean refund, XmlElement block) {
        String name = null;
        for (XmlElement part : parts.values()) {
            String cat = part.requiredAttr("cat");
            if (name != null && !name.equals(cat)) {
                throw new CpjFormatException("one tax with two @cat names at " + block.path());
            }
            name = cat;
        }
        return new Ticket.Tax(new Codes.Tax(sysid, name), refund,
                money(required(parts, "taxAmt", block)), rate(required(parts, "taxRate", block)),
                money(required(parts, "taxNet", block)), required(parts, "taxAttribute", block).requiredText(),
                Optional.ofNullable(parts.get("taxExempt")).map(TicketTaxHandler::money),
                Optional.ofNullable(parts.get("taxExemptAgainst")).map(TicketTaxHandler::money));
    }

    private static XmlElement required(Map<String, XmlElement> parts, String name, XmlElement block) {
        XmlElement part = parts.get(name);
        if (part == null) {
            throw new CpjFormatException("missing " + name + " for a tax in " + block.path());
        }
        return part;
    }

    private static BigDecimal money(XmlElement e) {
        return Values.decimal(e.requiredText(), Values.MONEY_SCALE, e.path());
    }

    private static BigDecimal rate(XmlElement e) {
        return Values.decimal(e.requiredText(), Values.QUANTITY_SCALE, e.path());
    }
}
