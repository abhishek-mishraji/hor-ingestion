package com.handsoffretail.verifone.parse.handlers;

import java.util.List;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.PeriodClose;

/** trans type="periodClose": a shift or day close (collected and written at the end of the file). */
final class PeriodCloseParser implements TransParser {

    private final HandlerRegistry<HeaderDraft> header = new HandlerRegistry<>(List.of(new HeaderMomentHandler(),
            new HeaderTerminalHandler(), new PeriodCloseHeaderHandler()));

    @Override
    public void parse(XmlElement trans, int seq, ParseState state) {
        trans.requiredAttr("type");
        HeaderDraft h = new HeaderDraft();
        header.dispatchChildren(trans.requiredChild("trHeader"), h);
        trans.verifyFullyUsed();
        String where = trans.path() + " #" + seq;
        h.storeNumber().ifPresent(n -> state.storeNumber(n, where));
        state.periodCloses.add(new PeriodClose(
                h.periodType().orElseThrow(() -> missing("periodType", where)),
                h.periodSeq().orElseThrow(() -> missing("periodSeq", where)),
                h.periodName().orElseThrow(() -> missing("periodName", where)),
                h.at().orElseThrow(() -> missing("date", where)),
                h.uniqueId().orElseThrow(() -> missing("uniqueID", where)),
                h.mwsEnabled()));
    }

    private static CpjFormatException missing(String element, String where) {
        return new CpjFormatException("missing trHeader/" + element + " in " + where);
    }
}
