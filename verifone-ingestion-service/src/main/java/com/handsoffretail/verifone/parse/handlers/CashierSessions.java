package com.handsoffretail.verifone.parse.handlers;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.CashierSession;
import com.handsoffretail.verifone.parse.model.Ticket;

/**
 * openCashier / closeCashier records. The {@code <trans>} copy is stored (one session per register
 * and cashier period); the root-level copy repeats it and must be equal (a hard load check), so it
 * is compared, not stored.
 */
final class CashierSessions {

    private static final String OPEN = "openCashier";
    private static final String HEADER = "trHeader";

    private final Map<String, Draft> sessions = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> transCopies = new LinkedHashMap<>();
    private final List<RootCopy> rootCopies = new ArrayList<>();
    private final Map<String, Integer> identicalRepeats = new TreeMap<>();

    /**
     * A {@code <trans type="openCashier|closeCashier">}. Some registers (1409_UT-84790) write each one
     * twice in a row: an identical repeat is counted and dropped, a different one fails the load.
     */
    void transCopy(XmlElement trans, String kind) {
        String repeatKey = kind + "/" + Values.intOf(trans.requiredChild("posNum")) + "/"
                + Values.intOf(trans.requiredChild("cashierPeriod"));
        Map<String, String> earlier = transCopies.get(repeatKey);
        if (earlier != null) {
            if (!earlier.equals(canonical(trans))) {
                throw new CpjFormatException("two different " + kind + " records for register / period "
                        + repeatKey.substring(kind.length() + 1) + " at " + trans.path());
            }
            identicalRepeats.merge(kind, 1, Integer::sum);
            return;
        }
        XmlElement header = trans.requiredChild(HEADER);
        Ticket.Cashier cashier = CashierHandler.cashier(trans.requiredChild("cashier"), CashierHandler.ROLE_CASHIER);
        int posNum = Values.intOf(trans.requiredChild("posNum"));
        int period = Values.intOf(trans.requiredChild("cashierPeriod"));
        requireSame(canonical(header.requiredChild("cashier")), canonical(trans.requiredChild("cashier")), trans,
                "trHeader/cashier");
        if (Values.intOf(header.requiredChild("posNum")) != posNum || cashier.posNum() != posNum
                || cashier.period() != period) {
            throw new CpjFormatException("register / cashier period disagree inside " + trans.path());
        }
        String key = posNum + "/" + period;
        Draft draft = sessions.computeIfAbsent(key, k -> new Draft(posNum, period, cashier));
        if (!draft.cashier.cashier().equals(cashier.cashier()) || draft.cashier.drawer() != cashier.drawer()) {
            throw new CpjFormatException("open and close of register " + posNum + " period " + period
                    + " name different cashiers or drawers at " + trans.path());
        }
        draft.openedAt(Values.moment(trans.requiredChildText("cOpenTime"), trans.path() + "/cOpenTime"), trans);
        if (kind.equals(OPEN)) {
            XmlElement begin = trans.requiredChild("beginCash");
            draft.beginCash = new CashierSession.BeginCash(Values.decimal(begin.requiredText(), Values.MONEY_SCALE,
                    begin.path()), Values.requiredIntAttr(begin, "mop"), Values.requiredIntAttr(begin, "cat"));
        } else {
            draft.close = new CashierSession.Close(
                    Values.moment(trans.requiredChildText("cCloseTime"), trans.path() + "/cCloseTime"),
                    Values.requiredDecimal(trans, "beginCash", Values.MONEY_SCALE),
                    Values.requiredDecimal(trans, "beginFoodStmp", Values.MONEY_SCALE),
                    Values.longValue(trans.requiredChildText("beginTicket"), trans.path() + "/beginTicket"),
                    Values.longValue(trans.requiredChildText("endTicket"), trans.path() + "/endTicket"),
                    Values.intOf(trans.requiredChild("ticketCount")),
                    header.child("mwsEnabled").map(m -> Values.flag01(m.requiredText(), m.path())));
        }
        transCopies.put(kind + "/" + key, canonical(trans));
    }

    /** A root-level {@code <openCashier>} / {@code <closeCashier>}. */
    void rootCopy(XmlElement root) {
        rootCopies.add(new RootCopy(root.name() + "/" + Values.intOf(root.requiredChild("posNum")) + "/"
                + Values.intOf(root.requiredChild("cashierPeriod")), canonical(root), root.path()));
    }

    List<CashierSession> sessions() {
        return sessions.values().stream().map(Draft::build).toList();
    }

    /** Identical repeated trans per type (openCashier / closeCashier), dropped after comparing. */
    Map<String, Integer> identicalRepeats() {
        return identicalRepeats;
    }

    int rootCopies() {
        return rootCopies.size();
    }

    int rootCopiesEqual() {
        return (int) rootCopies.stream().filter(r -> r.values().equals(transCopies.get(r.key()))).count();
    }

    List<String> problems() {
        return rootCopies.stream().filter(r -> !r.values().equals(transCopies.get(r.key())))
                .map(r -> transCopies.containsKey(r.key()) ? r.path() + " " + r.key() + " differs from its trans copy"
                        : r.path() + " " + r.key() + " has no trans copy")
                .toList();
    }

    /**
     * Every value of an element as name -> text and name@attribute -> value (reading marks it). A
     * trans copy's trHeader is folded in, except that a root copy keeps mwsEnabled at top level.
     */
    private static Map<String, String> canonical(XmlElement element) {
        Map<String, String> values = new TreeMap<>();
        collect(element, "", values);
        return values;
    }

    private static void collect(XmlElement element, String prefix, Map<String, String> values) {
        element.markUsed();
        String name = prefix.isEmpty() ? "." : prefix;
        element.text().ifPresent(t -> values.put(name, t));
        for (String attribute : element.attributeNames()) {
            String value = element.attr(attribute).orElse("");
            if (!prefix.isEmpty()) {
                // the record's own attributes (@type of the trans copy) are not part of the compared values
                values.put(name + "@" + attribute, value);
            }
        }
        for (XmlElement child : element.allChildren()) {
            if (child.name().equals(HEADER) && prefix.isEmpty()) {
                child.markUsed();
                for (XmlElement inHeader : child.allChildren()) {
                    // trHeader's cashier and posNum are checked against the record's own; mwsEnabled is a value
                    collect(inHeader, inHeader.name().equals("mwsEnabled") ? "mwsEnabled" : HEADER + "/" + inHeader.name(),
                            inHeader.name().equals("mwsEnabled") ? values : new TreeMap<>());
                }
            } else {
                collect(child, prefix.isEmpty() ? child.name() : prefix + "/" + child.name(), values);
            }
        }
    }

    private static void requireSame(Map<String, String> a, Map<String, String> b, XmlElement trans, String what) {
        if (!a.equals(b)) {
            throw new CpjFormatException(what + " differs from the record's cashier in " + trans.path());
        }
    }

    private record RootCopy(String key, Map<String, String> values, String path) {
    }

    private static final class Draft {
        final int posNum;
        final int period;
        final Ticket.Cashier cashier;
        OffsetDateTime openedAt;
        CashierSession.BeginCash beginCash;
        CashierSession.Close close;

        Draft(int posNum, int period, Ticket.Cashier cashier) {
            this.posNum = posNum;
            this.period = period;
            this.cashier = cashier;
        }

        void openedAt(OffsetDateTime time, XmlElement trans) {
            if (openedAt != null && !openedAt.isEqual(time)) {
                throw new CpjFormatException("open and close disagree on cOpenTime at " + trans.path());
            }
            openedAt = time;
        }

        CashierSession build() {
            return new CashierSession(posNum, period, cashier.cashier(), cashier.drawer(), openedAt,
                    Optional.ofNullable(beginCash), Optional.ofNullable(close));
        }
    }
}
