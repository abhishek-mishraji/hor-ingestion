package com.handsoffretail.verifone.parse.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.CashierEvent;
import com.handsoffretail.verifone.parse.model.Ticket;

/**
 * Journal {@code <trans>}. The six cashier-event kinds are kept (owner's choice) with their header,
 * lines, drawer totals or price check. The other journal text is not stored (owner's decision): the
 * five kinds seen in the sample are skipped after checking their shape; an unknown kind fails.
 */
final class JournalParser implements TransParser {

    static final Map<String, String> KEPT = Map.of(
            "CHANGE QTY", "CHANGE_QTY",
            "ERROR CORRECT", "ERROR_CORRECT",
            "LOGIN/LOGOUT", "LOGIN_LOGOUT",
            "SAFE DROP EVENT", "SAFE_DROP",
            "PAY OUT EVENT", "PAY_OUT",
            "PRICE CHECK", "PRICE_CHECK");
    /**
     * Journal text kinds known to carry text only (header + trjText + marker), not stored. The first
     * five are from the 20-day sample; the last two were found in older S3 files (2026-01-01,
     * 2026-03-01), each once, with the same text-only shape.
     */
    static final Set<String> SKIPPED = Set.of("SECURE USER", "EPS MESSAGE", "OTHER", "DCR EVENT", "FUEL EVENT",
            "CAR WASH DEVICE STATUS EVENT", "SALES EVENT");
    private static final Set<String> SKIPPED_HEADER = Set.of("cashier", "date", "duration", "period", "posNum",
            "storeNumber", "termMsgSN", "uniqueID");
    private static final Set<String> SKIPPED_JOURNAL = Set.of("marker", "trjText");
    private static final Pattern CHANGE_QTY = Pattern.compile("^CHANGE QTY (.*) FROM (\\S+) TO (\\S+)$");

    private final HandlerRegistry<HeaderDraft> header = new HandlerRegistry<>(List.of(new HeaderMomentHandler(),
            new HeaderTerminalHandler(), new CashierHandler()));
    private final HandlerRegistry<EventDraft> journal;

    JournalParser(LineParser lines) {
        this.journal = new HandlerRegistry<>(List.of(new JournalTextHandler(), new EventLineHandler(lines),
                new DrawerTotalsHandler(), new PriceCheckHandler()));
    }

    @Override
    public void parse(XmlElement trans, int seq, ParseState state) {
        trans.requiredAttr("type");
        XmlElement trJournal = trans.requiredChild("trJournal");
        String textType = trJournal.requiredChild("trjText").requiredAttr("type");
        String where = trans.path() + " #" + seq;
        if (KEPT.containsKey(textType)) {
            keep(trans, trJournal, KEPT.get(textType), seq, where, state);
        } else if (SKIPPED.contains(textType)) {
            skip(trans, trJournal, textType, where, state);
        } else {
            throw new CpjFormatException("unknown journal text type '" + textType + "' at " + where + "/trJournal/trjText/@type");
        }
    }

    private void keep(XmlElement trans, XmlElement trJournal, String eventType, int seq, String where, ParseState state) {
        HeaderDraft h = new HeaderDraft();
        header.dispatchChildren(trans.requiredChild("trHeader"), h);
        EventDraft d = new EventDraft();
        journal.dispatchChildren(trJournal, d);
        trans.verifyFullyUsed();
        int posNum = h.headerPosNum().orElseThrow(() -> new CpjFormatException("no trHeader/posNum in " + where));
        Optional<Ticket.Cashier> cashier = h.cashier();
        if (cashier.isPresent() && cashier.get().posNum() != posNum) {
            throw new CpjFormatException("cashier/@posNum differs from trHeader/posNum in " + where);
        }
        h.storeNumber().ifPresent(n -> state.storeNumber(n, where));
        String text = d.text.strip();
        state.cashierEventRead();
        state.sink.cashierEvent(new CashierEvent(seq, eventType,
                h.at().orElseThrow(() -> new CpjFormatException("missing date in " + where)), posNum,
                cashier.map(Ticket.Cashier::cashier), h.cashierEntryMode(), cashier.map(Ticket.Cashier::period),
                cashier.map(Ticket.Cashier::drawer), h.termMsgSn(), h.termMsgType(), h.terminalNo(), h.uniqueId(),
                h.durationSec(), h.hourSeq(), h.shiftSeq(), h.daySeq(), text, quantityChange(eventType, text, where),
                d.lines, d.drawerTotals, Optional.ofNullable(d.priceCheck)));
    }

    private static void skip(XmlElement trans, XmlElement trJournal, String textType, String where, ParseState state) {
        XmlElement trHeader = trans.requiredChild("trHeader");
        trHeader.child("storeNumber").flatMap(XmlElement::text).ifPresent(n -> state.storeNumber(n, where));
        trHeader.skipKnown(SKIPPED_HEADER);
        trJournal.skipKnown(SKIPPED_JOURNAL);
        trans.verifyFullyUsed();
        state.journalSkipped(textType);
    }

    private static Optional<CashierEvent.QuantityChange> quantityChange(String eventType, String text, String where) {
        if (!eventType.equals("CHANGE_QTY")) {
            return Optional.empty();
        }
        Matcher m = CHANGE_QTY.matcher(text);
        if (!m.matches()) {
            throw new CpjFormatException("CHANGE QTY text not in the form 'CHANGE QTY <item> FROM <a> TO <b>' at " + where);
        }
        BigDecimal from = Values.decimal(m.group(2), Values.QUANTITY_SCALE, where);
        BigDecimal to = Values.decimal(m.group(3), Values.QUANTITY_SCALE, where);
        return Optional.of(new CashierEvent.QuantityChange(m.group(1), from, to));
    }
}
