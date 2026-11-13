package com.handsoffretail.verifone.parse.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.DobDecoder;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Ticket;

/**
 * Ticket {@code <trans>}: the types of {@code ck_tickets_type}. The register is trTickNum/posNum,
 * else trHeader/posNum; the birth date is decoded against the ticket day.
 */
final class TicketParser implements TransParser {

    static final Set<String> TYPES = Set.of("sale", "network sale", "refund sale", "refund void", "void", "nosale",
            "safedrop", "safeloan", "payout", "fuelpriceChange");

    private final HandlerRegistry<TicketDraft> handlers;

    TicketParser(LineParser lines) {
        this.handlers = new HandlerRegistry<>(List.of(new TicketHeaderHandler(), new TicketValueHandler(),
                new TicketLinesHandler(lines), new PaymentHandler(), new NetworkProductHandler(),
                new TicketLoyaltyHandler(), new CouponHandler(), new PriceChangeHandler()));
    }

    @Override
    public void parse(XmlElement trans, int seq, ParseState state) {
        String type = trans.requiredAttr("type");
        Ticket.Flags flags = new Ticket.Flags(Values.boolAttr(trans, "suspended"), Values.boolAttr(trans, "fuelPrepay"),
                Values.boolAttr(trans, "rollback"), Values.boolAttr(trans, "fuelPrepayCompletion"),
                Values.boolAttr(trans, "recalled"));
        TicketDraft d = new TicketDraft();
        handlers.dispatchChildren(trans, d);
        trans.verifyFullyUsed();
        HeaderDraft h = d.header;
        String where = trans.path() + " #" + seq;
        int posNum = register(h, where);
        h.storeNumber().ifPresent(n -> state.storeNumber(n, where));
        h.physicalRegisterId().ifPresent(id -> state.physicalRegister(posNum, id, where));
        if (d.couponTotal != null || !d.coupons.isEmpty()) {
            state.coupons(Optional.ofNullable(d.couponTotal).orElse(BigDecimal.ZERO),
                    d.coupons.stream().map(Ticket.Coupon::discount).reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        Ticket.Header header = new Ticket.Header(h.at().orElseThrow(() -> new CpjFormatException("missing date in " + where)),
                h.trSeq(), h.termMsgType(), h.terminalNo(), h.termMsgSn(), h.uniqueId(), h.uniqueSn(), h.durationSec(),
                h.hourSeq(), h.shiftSeq(), h.daySeq(), h.till(), h.original(), h.recall(), h.miscRef(), h.houseAccount(),
                h.fuelOnlyCustomer());
        state.ticketRead(type);
        state.sink.ticket(new Ticket(seq, type, flags, posNum, h.physicalRegisterId(), header,
                Optional.ofNullable(d.value).map(v -> value(v, header)), h.cashiers(), d.lines, d.payments,
                d.networkProducts, d.loyalty, d.coupons, Optional.ofNullable(d.couponTotal), priceChange(d, where)));
    }

    private static int register(HeaderDraft h, String where) {
        if (h.ticketPosNum().isPresent() && h.headerPosNum().isPresent()
                && !h.ticketPosNum().equals(h.headerPosNum())) {
            throw new CpjFormatException("trTickNum/posNum and trHeader/posNum differ in " + where);
        }
        return h.ticketPosNum().or(h::headerPosNum)
                .orElseThrow(() -> new CpjFormatException("no register (posNum) in " + where));
    }

    private static Ticket.Value value(ValueDraft v, Ticket.Header header) {
        Optional<Ticket.AgeCheck> age = Optional.ofNullable(v.ageCheck).map(a -> new Ticket.AgeCheck(
                DobDecoder.decode(a.dob(), a.customerAge(), header.at().toLocalDate(), a.path()),
                a.customerAge(), a.minAge(), a.entryMethod()));
        return new Ticket.Value(Optional.ofNullable(v.currency), Optional.ofNullable(v.total),
                Optional.ofNullable(v.totalNoTax), Optional.ofNullable(v.totalTax), Optional.ofNullable(v.totalWithTax),
                Optional.ofNullable(v.sTotalizer), Optional.ofNullable(v.gTotalizer), Optional.ofNullable(v.recallAmount),
                v.taxes, Optional.ofNullable(v.foodStamp), age);
    }

    /** trPriceChange and the header's priceChangedBy belong together (cpj.fuel_price_changes). */
    private static Optional<Ticket.PriceChange> priceChange(TicketDraft d, String where) {
        HeaderDraft h = d.header;
        if (!d.hasPriceChange) {
            if (h.priceChangedBy().isPresent() || h.priceChangeOrigin().isPresent()) {
                throw new CpjFormatException("priceChangedBy without trPriceChange in " + where);
            }
            return Optional.empty();
        }
        String by = h.priceChangedBy().orElseThrow(() -> new CpjFormatException("trPriceChange without priceChangedBy in " + where));
        return Optional.of(new Ticket.PriceChange(by, h.priceChangeOrigin(), d.priceChangeEntries));
    }
}
