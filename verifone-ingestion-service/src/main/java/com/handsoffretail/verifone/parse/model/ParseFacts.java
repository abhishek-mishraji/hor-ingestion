package com.handsoffretail.verifone.parse.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * What the main parser observed that the load checks need: header/site agreement, root copies of
 * the cashier records, coupon totals, register identities, and what was read or skipped.
 * Problem lists hold element paths and sequence numbers only (never personal data).
 */
public record ParseFacts(
        int transCount,
        Map<String, Integer> ticketsByType,
        int cashierEvents,
        Map<String, Integer> skippedJournal,
        Map<String, Integer> skippedTrans,
        int storeNumbersChecked,
        int storeNumberMismatchCount,
        List<String> storeNumberMismatches,
        int rootCopies,
        int rootCopiesEqual,
        List<String> rootCopyProblems,
        BigDecimal couponTotalInFile,
        BigDecimal couponSum,
        int ticketsWithCouponMismatch,
        Map<Integer, Integer> physicalRegisterIds,
        List<String> registerConflicts) {

    public ParseFacts {
        ticketsByType = Map.copyOf(ticketsByType);
        skippedJournal = Map.copyOf(skippedJournal);
        skippedTrans = Map.copyOf(skippedTrans);
        storeNumberMismatches = List.copyOf(storeNumberMismatches);
        rootCopyProblems = List.copyOf(rootCopyProblems);
        physicalRegisterIds = Map.copyOf(physicalRegisterIds);
        registerConflicts = List.copyOf(registerConflicts);
    }
}
