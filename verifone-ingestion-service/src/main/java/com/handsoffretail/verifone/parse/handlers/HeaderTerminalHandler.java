package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;

/** trHeader termMsgSN (@type, @term, number), uniqueID, posNum and storeNumber. */
final class HeaderTerminalHandler implements ElementHandler<HeaderDraft> {

    static final Set<String> TERM_MSG_TYPES = Set.of("FINANCIAL", "JOURNAL");

    @Override
    public Set<String> elements() {
        return Set.of("termMsgSN", "uniqueID", "posNum", "storeNumber");
    }

    @Override
    public void handle(XmlElement e, HeaderDraft draft) {
        switch (e.name()) {
            case "termMsgSN" -> termMsg(e, draft);
            case "uniqueID" -> draft.uniqueId = e.text().orElse(null);
            case "posNum" -> draft.headerPosNum = Values.intOf(e);
            default -> draft.storeNumber = e.text().orElse(null);
        }
    }

    private static void termMsg(XmlElement e, HeaderDraft draft) {
        draft.termMsgType = e.attr("type").orElse(null);
        if (draft.termMsgType != null && !TERM_MSG_TYPES.contains(draft.termMsgType)) {
            throw new CpjFormatException("unknown termMsgSN type '" + draft.termMsgType + "' at " + e.path());
        }
        draft.terminalNo = Values.optionalIntAttr(e, "term").orElse(null);
        draft.termMsgSn = e.text().map(t -> Values.longValue(t, e.path())).orElse(null);
    }
}
