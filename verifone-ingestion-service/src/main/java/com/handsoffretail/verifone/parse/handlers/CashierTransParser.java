package com.handsoffretail.verifone.parse.handlers;

import com.handsoffretail.verifone.parse.XmlElement;

/** trans type="openCashier" / "closeCashier": one side of a cashier session. */
final class CashierTransParser implements TransParser {

    @Override
    public void parse(XmlElement trans, int seq, ParseState state) {
        String kind = trans.requiredAttr("type");
        state.sessions.transCopy(trans, kind);
        trans.verifyFullyUsed();
    }
}
