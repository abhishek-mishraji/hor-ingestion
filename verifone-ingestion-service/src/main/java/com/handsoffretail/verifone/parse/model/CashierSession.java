package com.handsoffretail.verifone.parse.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/** openCashier + closeCashier of one cashier period on one register (either may be missing in a file). */
public record CashierSession(int posNum, int cashierPeriod, Codes.Cashier cashier, int drawer, OffsetDateTime openedAt,
        Optional<BeginCash> beginCash, Optional<Close> close) {

    public CashierSession {
        Objects.requireNonNull(cashier, "cashier");
        Objects.requireNonNull(openedAt, "openedAt");
        Objects.requireNonNull(beginCash, "beginCash");
        Objects.requireNonNull(close, "close");
    }

    /** openCashier/beginCash (300.00) with @mop and @cat. */
    public record BeginCash(BigDecimal amount, int mopCode, int mopCategory) {
    }

    /** closeCashier fields. */
    public record Close(OffsetDateTime closedAt, BigDecimal beginCash, BigDecimal beginFoodStamp, long beginTicket,
            long endTicket, int ticketCount, Optional<Boolean> mwsEnabled) {
    }
}
