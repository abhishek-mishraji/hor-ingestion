package com.handsoffretail.verifone.parse.handlers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.handsoffretail.verifone.parse.model.CpjSink;
import com.handsoffretail.verifone.parse.model.ParseFacts;
import com.handsoffretail.verifone.parse.model.PeriodClose;

/** Mutable state of one file's parse: the sink, the site, the collected facts and end-of-file records. */
final class ParseState {

    /** Problem lists keep this many examples; the count says how many there were. */
    private static final int MAX_EXAMPLES = 5;

    final CpjSink sink;
    final String site;
    final CashierSessions sessions = new CashierSessions();
    final List<PeriodClose> periodCloses = new ArrayList<>();
    private final Map<String, Integer> ticketsByType = new TreeMap<>();
    private final Map<String, Integer> skippedJournal = new TreeMap<>();
    private final List<String> storeNumberMismatches = new ArrayList<>();
    private final Map<Integer, Integer> physicalRegisterIds = new HashMap<>();
    private final List<String> registerConflicts = new ArrayList<>();
    private int transCount;
    private int cashierEvents;
    private int storeNumbersChecked;
    private int storeNumberMismatchCount;
    private BigDecimal couponTotalInFile = BigDecimal.ZERO;
    private BigDecimal couponSum = BigDecimal.ZERO;
    private int ticketsWithCouponMismatch;

    ParseState(CpjSink sink, String site) {
        this.sink = sink;
        this.site = site;
    }

    int nextSeq() {
        return ++transCount;
    }

    void ticketRead(String type) {
        ticketsByType.merge(type, 1, Integer::sum);
    }

    void cashierEventRead() {
        cashierEvents++;
    }

    void journalSkipped(String textType) {
        skippedJournal.merge(textType, 1, Integer::sum);
    }

    /** Every trHeader/storeNumber must equal transSet/@site (a hard load check). */
    void storeNumber(String storeNumber, String where) {
        storeNumbersChecked++;
        if (!site.equals(storeNumber)) {
            storeNumberMismatchCount++;
            if (storeNumberMismatches.size() < MAX_EXAMPLES) {
                storeNumberMismatches.add(where);
            }
        }
    }

    /** One posNum must never show two physicalRegisterIDs (a hard load check). */
    void physicalRegister(int posNum, int physicalId, String where) {
        Integer known = physicalRegisterIds.putIfAbsent(posNum, physicalId);
        if (known != null && known != physicalId && registerConflicts.size() < MAX_EXAMPLES) {
            registerConflicts.add("register " + posNum + " shows physical ids " + known + " and " + physicalId + " (" + where + ")");
        }
    }

    void coupons(BigDecimal totalInFile, BigDecimal sum) {
        couponTotalInFile = couponTotalInFile.add(totalInFile);
        couponSum = couponSum.add(sum);
        if (totalInFile.compareTo(sum) != 0) {
            ticketsWithCouponMismatch++;
        }
    }

    ParseFacts facts() {
        return new ParseFacts(transCount, ticketsByType, cashierEvents, skippedJournal, storeNumbersChecked,
                storeNumberMismatchCount, storeNumberMismatches,
                sessions.rootCopies(), sessions.rootCopiesEqual(), sessions.problems(), couponTotalInFile, couponSum,
                ticketsWithCouponMismatch, physicalRegisterIds, registerConflicts);
    }
}
