package com.handsoffretail.verifone.parse.handlers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.handsoffretail.verifone.parse.model.Line;
import com.handsoffretail.verifone.parse.model.Payment;
import com.handsoffretail.verifone.parse.model.Ticket;

/** What the handlers of a ticket {@code <trans>} found. */
final class TicketDraft {

    final HeaderDraft header = new HeaderDraft();
    ValueDraft value;
    final List<Line> lines = new ArrayList<>();
    final List<Payment> payments = new ArrayList<>();
    final List<Ticket.NetworkProduct> networkProducts = new ArrayList<>();
    final List<Ticket.Loyalty> loyalty = new ArrayList<>();
    final List<Ticket.Coupon> coupons = new ArrayList<>();
    BigDecimal couponTotal;
    final List<Ticket.PriceChangeEntry> priceChangeEntries = new ArrayList<>();
    boolean hasPriceChange;
}
