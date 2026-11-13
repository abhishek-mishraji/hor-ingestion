package com.handsoffretail.verifone.parse.handlers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.handsoffretail.verifone.parse.model.Ticket;

/** What the trValue handlers found. The birth date is decoded once the ticket day is known. */
final class ValueDraft {

    String currency;
    BigDecimal total;
    BigDecimal totalNoTax;
    BigDecimal totalTax;
    BigDecimal totalWithTax;
    BigDecimal sTotalizer;
    BigDecimal gTotalizer;
    BigDecimal recallAmount;
    final List<Ticket.Tax> taxes = new ArrayList<>();
    Ticket.FoodStamp foodStamp;
    RawAgeCheck ageCheck;

    /** custDOB as written; @dob is decoded against the ticket day in {@link TicketParser}. */
    record RawAgeCheck(String dob, int customerAge, int minAge, String entryMethod, String path) {

        @Override
        public String toString() {
            return "RawAgeCheck[age=" + customerAge + "]";
        }
    }
}
