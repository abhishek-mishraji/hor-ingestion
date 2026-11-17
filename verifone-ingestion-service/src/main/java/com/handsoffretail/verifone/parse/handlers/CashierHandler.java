package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Codes;
import com.handsoffretail.verifone.parse.model.Ticket;

/**
 * trHeader/cashier (and, in {@link OriginalCashierHandler}, originalCashier): @sysid, text = name,
 * @empNum, @posNum, @period, @drawer, and on journal logins @entryMode.
 */
final class CashierHandler implements ElementHandler<HeaderDraft> {

    static final String ROLE_CASHIER = "CASHIER";
    static final String ROLE_ORIGINAL = "ORIGINAL";

    @Override
    public Set<String> elements() {
        return Set.of("cashier");
    }

    @Override
    public void handle(XmlElement e, HeaderDraft draft) {
        draft.cashiers.add(cashier(e, ROLE_CASHIER));
        draft.cashierEntryMode = e.attr("entryMode").orElse(null);
    }

    /** Reads one cashier element (also used for the cashier blocks of open/close cashier records). */
    static Ticket.Cashier cashier(XmlElement e, String role) {
        Codes.Cashier code = new Codes.Cashier(Values.requiredIntAttr(e, "sysid"), e.text().orElse(""),
                e.attr("empNum").map(String::strip).filter(v -> !v.isEmpty()));
        return new Ticket.Cashier(role, code, Values.requiredIntAttr(e, "posNum"), Values.requiredIntAttr(e, "period"),
                Values.requiredIntAttr(e, "drawer"));
    }
}
