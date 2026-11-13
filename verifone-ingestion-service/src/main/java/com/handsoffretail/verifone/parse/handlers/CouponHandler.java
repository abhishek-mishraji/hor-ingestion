package com.handsoffretail.verifone.parse.handlers;

import java.util.List;
import java.util.Set;

import com.handsoffretail.verifone.parse.UnmappedInputException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Ticket;

/**
 * trScannedCoupons: couponDisc entries and couponDiscTotal (not stored: it is their sum, checked by
 * the coupon-total load check).
 */
final class CouponHandler implements ElementHandler<TicketDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("trScannedCoupons");
    }

    @Override
    public void handle(XmlElement e, TicketDraft d) {
        for (XmlElement child : e.allChildren()) {
            if (!child.name().equals("couponDisc") && !child.name().equals("couponDiscTotal")) {
                throw new UnmappedInputException("element " + child.path());
            }
        }
        List<XmlElement> coupons = e.children("couponDisc");
        for (int i = 0; i < coupons.size(); i++) {
            XmlElement c = coupons.get(i);
            XmlElement affects = c.requiredChild("affectsTax");
            d.coupons.add(new Ticket.Coupon(i + 1, Values.requiredDecimal(c, "discountAmt", Values.MONEY_SCALE),
                    Values.bool(affects.requiredText(), affects.path()), c.requiredChildText("entryMethod"),
                    c.childText("couponData")));
        }
        d.couponTotal = Values.optionalDecimal(e, "couponDiscTotal", Values.MONEY_SCALE).orElse(null);
    }
}
