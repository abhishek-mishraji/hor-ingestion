package com.handsoffretail.verifone.parse.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * One {@code <trPayline>}; type "cancel" is money handed back on a refund. {@code couponEntryMethod}
 * is trpCouponEntryMethod (V6; seen on a COUPON tender), as written. {@code houseAccount} is
 * trpHouseAcct (an IN-HOUSE tender charged to a house account).
 */
public record Payment(int lineNo, String type, Codes.PaymentType paymentType, BigDecimal amount,
        Optional<String> currency, Optional<Integer> posSysid, boolean duplicate, boolean pending,
        Optional<BigDecimal> originalPreauth, Optional<Card> card, Optional<String> couponEntryMethod,
        Optional<Codes.HouseAccount> houseAccount) {

    public Payment {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(paymentType, "paymentType");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(couponEntryMethod, "couponEntryMethod");
        Objects.requireNonNull(houseAccount, "houseAccount");
    }

    /** trpCardInfo. The account is the POS's masked form; it is never printed. */
    public record Card(String maskedNumber, String cardName, Optional<Integer> cardNameSysid, String entryMethod,
            Optional<String> authCode, Optional<OffsetDateTime> authAt, Optional<String> authSource,
            Optional<String> batchNo, Optional<String> acquirerBatchNo, Optional<String> hostId,
            Optional<String> referenceNo, Optional<String> sequenceNo, Optional<String> hostTicket,
            Optional<Boolean> aboveSite) {

        @Override
        public String toString() {
            return "Card[" + cardName + ", " + entryMethod + "]";
        }
    }
}
