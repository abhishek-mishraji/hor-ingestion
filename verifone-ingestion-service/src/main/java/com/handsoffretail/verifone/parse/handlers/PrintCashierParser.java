package com.handsoffretail.verifone.parse.handlers;

import java.util.HashSet;
import java.util.Set;

import com.handsoffretail.verifone.parse.XmlElement;

/**
 * trans type="printCashier" and its root copy transSet/printCashier (seen on store 1409_UT-84790): a
 * cashier report was printed. Not stored by design (owner's decision): the known shape is checked,
 * the trans is counted, and anything else fails. drawerAmounts was empty on every sample, so content
 * there fails too until it is mapped.
 */
final class PrintCashierParser implements TransParser {

    static final String TYPE = "printCashier";
    private static final Set<String> HEADER = Set.of("cashier", "posNum");
    private static final Set<String> FIELDS = Set.of("cCloseTime", "cashierPeriod", "cReportTime", "reconciledByID",
            "reconciledByName");

    @Override
    public void parse(XmlElement trans, int seq, ParseState state) {
        trans.requiredAttr("type");
        trans.requiredChild("trHeader").skipKnown(HEADER);
        skipFields(trans, FIELDS);
        state.transSkipped(TYPE);
    }

    /** transSet/printCashier: the same record with cashier and posNum directly inside (no trHeader). */
    static void skipRootCopy(XmlElement copy) {
        Set<String> fields = new HashSet<>(FIELDS);
        fields.addAll(HEADER);
        skipFields(copy, fields);
    }

    /** drawerAmounts must be empty; the given fields must hold no elements; anything else unread fails. */
    private static void skipFields(XmlElement e, Set<String> fields) {
        e.requiredChild("drawerAmounts").requireEmpty();
        for (XmlElement child : e.allChildren()) {
            if (fields.contains(child.name())) {
                child.skipKnown(Set.of());
            }
        }
        e.verifyFullyUsed();
    }
}
