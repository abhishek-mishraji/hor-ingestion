package com.handsoffretail.verifone.parse.handlers;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.handsoffretail.verifone.parse.model.Codes;
import com.handsoffretail.verifone.parse.model.Ticket;

/** What the trHeader handlers found; tickets, cashier events and period closes each take their part. */
public final class HeaderDraft {

    OffsetDateTime at;
    Integer durationSec;
    Integer hourSeq;
    Integer shiftSeq;
    Integer daySeq;
    String termMsgType;
    Integer terminalNo;
    Long termMsgSn;
    String uniqueId;
    Integer headerPosNum;
    String storeNumber;
    final List<Ticket.Cashier> cashiers = new ArrayList<>();
    String cashierEntryMode;
    Integer ticketPosNum;
    Long trSeq;
    Ticket.OriginalTicket original;
    Ticket.Recall recall;
    Long uniqueSn;
    Integer physicalRegisterId;
    Integer till;
    String miscRef;
    boolean fuelOnlyCustomer;
    Codes.HouseAccount houseAccount;
    String priceChangedBy;
    String priceChangeOrigin;
    String periodName;
    Integer periodSeq;
    Integer periodType;
    Boolean mwsEnabled;

    public Optional<OffsetDateTime> at() {
        return Optional.ofNullable(at);
    }

    public Optional<Integer> durationSec() {
        return Optional.ofNullable(durationSec);
    }

    public Optional<Integer> hourSeq() {
        return Optional.ofNullable(hourSeq);
    }

    public Optional<Integer> shiftSeq() {
        return Optional.ofNullable(shiftSeq);
    }

    public Optional<Integer> daySeq() {
        return Optional.ofNullable(daySeq);
    }

    public Optional<String> termMsgType() {
        return Optional.ofNullable(termMsgType);
    }

    public Optional<Integer> terminalNo() {
        return Optional.ofNullable(terminalNo);
    }

    public Optional<Long> termMsgSn() {
        return Optional.ofNullable(termMsgSn);
    }

    public Optional<String> uniqueId() {
        return Optional.ofNullable(uniqueId);
    }

    public Optional<Integer> headerPosNum() {
        return Optional.ofNullable(headerPosNum);
    }

    public Optional<String> storeNumber() {
        return Optional.ofNullable(storeNumber);
    }

    public List<Ticket.Cashier> cashiers() {
        return List.copyOf(cashiers);
    }

    public Optional<Ticket.Cashier> cashier() {
        return cashiers.stream().filter(c -> c.role().equals(CashierHandler.ROLE_CASHIER)).findFirst();
    }

    public Optional<String> cashierEntryMode() {
        return Optional.ofNullable(cashierEntryMode);
    }

    public Optional<Integer> ticketPosNum() {
        return Optional.ofNullable(ticketPosNum);
    }

    public Optional<Long> trSeq() {
        return Optional.ofNullable(trSeq);
    }

    public Optional<Ticket.OriginalTicket> original() {
        return Optional.ofNullable(original);
    }

    public Optional<Ticket.Recall> recall() {
        return Optional.ofNullable(recall);
    }

    public Optional<Long> uniqueSn() {
        return Optional.ofNullable(uniqueSn);
    }

    public Optional<Integer> physicalRegisterId() {
        return Optional.ofNullable(physicalRegisterId);
    }

    public Optional<Integer> till() {
        return Optional.ofNullable(till);
    }

    public Optional<String> miscRef() {
        return Optional.ofNullable(miscRef);
    }

    public boolean fuelOnlyCustomer() {
        return fuelOnlyCustomer;
    }

    public Optional<Codes.HouseAccount> houseAccount() {
        return Optional.ofNullable(houseAccount);
    }

    public Optional<String> priceChangedBy() {
        return Optional.ofNullable(priceChangedBy);
    }

    public Optional<String> priceChangeOrigin() {
        return Optional.ofNullable(priceChangeOrigin);
    }

    public Optional<String> periodName() {
        return Optional.ofNullable(periodName);
    }

    public Optional<Integer> periodSeq() {
        return Optional.ofNullable(periodSeq);
    }

    public Optional<Integer> periodType() {
        return Optional.ofNullable(periodType);
    }

    public Optional<Boolean> mwsEnabled() {
        return Optional.ofNullable(mwsEnabled);
    }
}
