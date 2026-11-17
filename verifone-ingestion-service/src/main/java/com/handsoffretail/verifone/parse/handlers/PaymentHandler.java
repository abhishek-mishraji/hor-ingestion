package com.handsoffretail.verifone.parse.handlers;

import java.util.List;
import java.util.Set;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.UnmappedInputException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Codes;
import com.handsoffretail.verifone.parse.model.Payment;

/** trPaylines/trPayline with trpPaycode, trpAmt and the optional trpCardInfo and trpHouseAcct. */
final class PaymentHandler implements ElementHandler<TicketDraft> {

    static final Set<String> PAYLINE_TYPES = Set.of("sale", "cancel");

    @Override
    public Set<String> elements() {
        return Set.of("trPaylines");
    }

    @Override
    public void handle(XmlElement e, TicketDraft d) {
        for (XmlElement child : e.allChildren()) {
            if (!child.name().equals("trPayline")) {
                throw new UnmappedInputException("element " + child.path());
            }
        }
        List<XmlElement> lines = e.children("trPayline");
        for (int i = 0; i < lines.size(); i++) {
            d.payments.add(payment(lines.get(i), i + 1));
        }
    }

    private static Payment payment(XmlElement p, int lineNo) {
        String type = p.requiredAttr("type");
        if (!PAYLINE_TYPES.contains(type)) {
            throw new CpjFormatException("unknown payline type '" + type + "' at " + p.path());
        }
        XmlElement code = p.requiredChild("trpPaycode");
        Codes.PaymentType paymentType = new Codes.PaymentType(Values.requiredIntAttr(code, "mop"), code.requiredText(),
                Values.requiredIntAttr(code, "cat"), code.requiredAttr("nacstendercode"),
                code.requiredAttr("nacstendersubcode"));
        return new Payment(lineNo, type, paymentType, Values.requiredDecimal(p, "trpAmt", Values.MONEY_SCALE),
                p.attr("locale"), Values.optionalIntAttr(p, "sysid"), Values.boolAttr(p, "duplicate"),
                Values.boolAttr(p, "pending"),
                p.attr("originalPreauth").map(v -> Values.decimal(v, Values.MONEY_SCALE, p.path() + "/@originalPreauth")),
                p.child("trpCardInfo").map(PaymentHandler::card), p.childText("trpCouponEntryMethod"),
                p.child("trpHouseAcct").map(h -> new Codes.HouseAccount(h.requiredText(), h.requiredAttr("name"))));
    }

    private static Payment.Card card(XmlElement c) {
        XmlElement name = c.requiredChild("trpcCCName");
        return new Payment.Card(c.requiredChildText("trpcAccount"), name.requiredText(),
                Values.optionalIntAttr(name, "sysid"), c.requiredChildText("trpcEntryMeth"),
                c.childText("trpcAuthCode"),
                c.child("trpcAuthDateTime").flatMap(x -> x.text().map(t -> Values.moment(t, x.path()))),
                c.childText("trpcAuthSrc"), c.childText("trpcBatchNr"), c.childText("trpcAcquirerBatchNr"),
                c.childText("trpcHostID"), c.childText("trpcRefNum"), c.childText("trpcSeqNr"), c.childText("trpcTicket"),
                c.attr("isAboveSite").map(v -> Values.bool(v, c.path() + "/@isAboveSite")));
    }
}
