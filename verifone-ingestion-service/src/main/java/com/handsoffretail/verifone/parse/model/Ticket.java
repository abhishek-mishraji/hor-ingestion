package com.handsoffretail.verifone.parse.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Every {@code <trans>} with a header except journal / openCashier / closeCashier / periodClose:
 * sales, refunds, voids, no-sales, safe drops, payouts, fuel price changes.
 */
public record Ticket(
        int seqInFile,
        String transType,
        Flags flags,
        int posNum,
        Optional<Integer> physicalRegisterId,
        Header header,
        Optional<Value> value,
        List<Cashier> cashiers,
        List<Line> lines,
        List<Payment> payments,
        List<NetworkProduct> networkProducts,
        List<Loyalty> loyalty,
        List<Coupon> coupons,
        Optional<BigDecimal> couponTotal,
        Optional<PriceChange> priceChange) {

    public Ticket {
        Objects.requireNonNull(transType, "transType");
        Objects.requireNonNull(flags, "flags");
        Objects.requireNonNull(header, "header");
        cashiers = List.copyOf(cashiers);
        lines = List.copyOf(lines);
        payments = List.copyOf(payments);
        networkProducts = List.copyOf(networkProducts);
        loyalty = List.copyOf(loyalty);
        coupons = List.copyOf(coupons);
    }

    /** The trans attributes @suspended, @fuelPrepay, @rollback, @fuelPrepayCompletion, @recalled. */
    public record Flags(boolean suspended, boolean fuelPrepay, boolean rollback, boolean prepayCompletion,
            boolean recalled) {
    }

    /** trHeader values a ticket stores. */
    public record Header(OffsetDateTime at, Optional<Long> trSeq, Optional<String> termMsgType,
            Optional<Integer> terminalNo, Optional<Long> termMsgSn, Optional<String> uniqueId, Optional<Long> uniqueSn,
            Optional<Integer> durationSec, Optional<Integer> hourSeq, Optional<Integer> shiftSeq,
            Optional<Integer> daySeq, Optional<Integer> till, Optional<OriginalTicket> original,
            Optional<Recall> recall, Optional<String> miscRef, Optional<Codes.HouseAccount> houseAccount,
            boolean fuelOnlyCustomer) {

        public Header {
            Objects.requireNonNull(at, "at");
        }
    }

    /** trOriginalTickNum: a prepay completion points at its prepay. */
    public record OriginalTicket(int posNum, long trSeq) {
    }

    /** trRecall. */
    public record Recall(String transType, int posNum, long trSeq) {
    }

    /** trHeader/cashier (role CASHIER) or trHeader/originalCashier (role ORIGINAL). */
    public record Cashier(String role, Codes.Cashier cashier, int posNum, int period, int drawer) {
    }

    /** trValue. */
    public record Value(Optional<String> currency, Optional<BigDecimal> total, Optional<BigDecimal> totalNoTax,
            Optional<BigDecimal> totalTax, Optional<BigDecimal> totalWithTax, Optional<BigDecimal> sTotalizer,
            Optional<BigDecimal> gTotalizer, Optional<BigDecimal> recallAmount, List<Tax> taxes,
            Optional<FoodStamp> foodStamp, Optional<AgeCheck> ageCheck) {

        public Value {
            taxes = List.copyOf(taxes);
        }
    }

    /** trTax/taxAmts (refund = false) or trTax/negTaxAmts (refund = true), per tax @sysid. */
    public record Tax(Codes.Tax tax, boolean refund, BigDecimal taxableAmount, BigDecimal rate, BigDecimal amount,
            String attribute, Optional<BigDecimal> exempt, Optional<BigDecimal> exemptAgainst) {
    }

    /** trFstmp. */
    public record FoodStamp(BigDecimal total, BigDecimal tax, BigDecimal change, BigDecimal tendered) {
    }

    /** custDOB. The birth date is personal data: never printed. */
    public record AgeCheck(LocalDate birthDate, int customerAge, int minAge, String entryMethod) {

        @Override
        public String toString() {
            return "AgeCheck[age=" + customerAge + "]";
        }
    }

    /** trExNetProds/trExNetProd. */
    public record NetworkProduct(int entryNo, String productCode, int itemCount, BigDecimal amount) {
    }

    /** trLoyalty/trLoyaltyProgram. The account number is never printed. */
    public record Loyalty(String programCode, Optional<String> account, Optional<String> authReply,
            Optional<BigDecimal> autoDiscount, Optional<BigDecimal> customerDiscount, Optional<String> entryMethod,
            Optional<BigDecimal> subtotal) {

        @Override
        public String toString() {
            return "Loyalty[" + programCode + "]";
        }
    }

    /** trScannedCoupons/couponDisc. */
    public record Coupon(int couponNo, BigDecimal discount, boolean affectsTax, String entryMethod,
            Optional<String> couponData) {
    }

    /** trans type="fuelpriceChange": who changed prices (trHeader) and each price change. */
    public record PriceChange(String changedBy, Optional<String> origin, List<PriceChangeEntry> entries) {

        public PriceChange {
            entries = List.copyOf(entries);
        }
    }

    /** trPriceChange/priceChangeInfo (sysids only; the file gives no names here). */
    public record PriceChangeEntry(int entryNo, Optional<Integer> infoSysid, int gradeSysid, int serviceModeSysid,
            int priceLevelSysid, int tierSysid, BigDecimal previousPrice, Optional<Integer> previousSysid,
            BigDecimal currentPrice, Optional<Integer> currentSysid) {
    }
}
