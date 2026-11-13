package com.handsoffretail.verifone.parse.handlers;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Codes;
import com.handsoffretail.verifone.parse.model.Line;

/**
 * Turns one {@code <trLine>} into a {@link Line}: the attributes here, the children through the
 * line handlers, then the line rules (IMPLEMENTATION_PLAN.md Phase 4 and V3 comments).
 */
public final class LineParser {

    static final Set<String> LINE_TYPES = Set.of("plu", "dept", "postFuel", "preFuel", "void plu", "void dept");
    private static final Set<String> ITEM_LINE_TYPES = Set.of("plu", "void plu");
    private static final String VOID_PREFIX = "void ";

    private final HandlerRegistry<LineDraft> handlers;

    LineParser(HandlerRegistry<LineDraft> handlers) {
        this.handlers = handlers;
    }

    static LineParser standard() {
        return new LineParser(new HandlerRegistry<>(List.of(new LineCoreHandler(), new LineFlagsHandler(),
                new LineFuelHandler(), new LineTaxHandler(), new LinePromotionHandler(), new LineLoyaltyHandler())));
    }

    Line parse(XmlElement e, int lineNo) {
        String type = e.requiredAttr("type");
        if (!LINE_TYPES.contains(type)) {
            throw new CpjFormatException("unknown line type '" + type + "' at " + e.path());
        }
        LineDraft d = new LineDraft(e.path());
        handlers.dispatchChildren(e, d);
        Codes.Department department = require(d.department, "trlDept", e);
        Optional<Codes.Item> item = item(type, d, e);
        Optional<String> posDescription = item.isPresent() || d.description == null
                || d.description.equals(department.name()) ? Optional.empty() : Optional.of(d.description);
        if (type.startsWith(VOID_PREFIX) != (d.voidedLineNo != null)) {
            throw new CpjFormatException("trlVoidLineIx must be present exactly on void lines at " + e.path());
        }
        return new Line(lineNo, type, Values.boolAttr(e, "duplicate"), Values.boolAttr(e, "unsettled"), department,
                Optional.ofNullable(d.category), item, Optional.ofNullable(d.upcEntryMethod),
                require(d.networkCode, "trlNetwCode", e), require(d.quantity, "trlQty", e), require(d.sign, "trlSign", e),
                Optional.ofNullable(d.sellUnit), require(d.unitPrice, "trlUnitPrice", e),
                require(d.lineTotal, "trlLineTot", e), Optional.ofNullable(d.priceOverride),
                Optional.ofNullable(d.discount), Optional.ofNullable(d.voidedLineNo), posDescription, d.flags,
                Optional.ofNullable(d.fuel), d.fuelDiscounts, d.loyaltyDiscounts, d.promotions, d.taxes);
    }

    /** plu / void plu lines carry an item (UPC + modifier + description + entry method); other lines none. */
    private static Optional<Codes.Item> item(String type, LineDraft d, XmlElement e) {
        boolean itemLine = ITEM_LINE_TYPES.contains(type);
        boolean hasItem = d.upc != null || d.modifier != null || d.upcEntryMethod != null;
        if (itemLine != hasItem) {
            throw new CpjFormatException((itemLine ? "item line without" : "non-item line with")
                    + " trlUPC / trlModifier / trlUPCEntry at " + e.path());
        }
        if (!itemLine) {
            return Optional.empty();
        }
        return Optional.of(new Codes.Item(require(d.upc, "trlUPC", e), require(d.modifier, "trlModifier", e),
                require(d.description, "trlDesc", e)));
    }

    private static <T> T require(T value, String element, XmlElement line) {
        if (value == null) {
            throw new CpjFormatException("missing " + line.path() + "/" + element);
        }
        return value;
    }
}
