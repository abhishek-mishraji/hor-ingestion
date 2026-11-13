package com.handsoffretail.verifone.parse.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A journal {@code <trans>} of one of the six kept kinds (CHANGE QTY, ERROR CORRECT, LOGIN/LOGOUT,
 * SAFE DROP EVENT, PAY OUT EVENT, PRICE CHECK), with its header (V5) and its lines, drawer totals
 * or price check.
 */
public record CashierEvent(
        int seqInFile,
        String eventType,
        OffsetDateTime at,
        int posNum,
        Optional<Codes.Cashier> cashier,
        Optional<String> cashierEntryMode,
        Optional<Integer> cashierPeriod,
        Optional<Integer> drawer,
        Optional<Long> termMsgSn,
        Optional<String> termMsgType,
        Optional<Integer> terminalNo,
        Optional<String> uniqueId,
        Optional<Integer> durationSec,
        Optional<Integer> hourSeq,
        Optional<Integer> shiftSeq,
        Optional<Integer> daySeq,
        String text,
        Optional<QuantityChange> quantityChange,
        List<Line> lines,
        List<DrawerTotal> drawerTotals,
        Optional<PriceCheck> priceCheck) {

    public CashierEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(text, "text");
        lines = List.copyOf(lines);
        drawerTotals = List.copyOf(drawerTotals);
    }

    /** CHANGE QTY &lt;item&gt; FROM &lt;a&gt; TO &lt;b&gt;. */
    public record QuantityChange(String itemDescription, BigDecimal from, BigDecimal to) {
    }

    /** trjDrTotals/trjdrTotal of a login/logout. */
    public record DrawerTotal(String tenderName, BigDecimal amount) {
    }

    /** priceCheckCancelItem: the item looked up. */
    public record PriceCheck(Codes.Item item, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }
}
