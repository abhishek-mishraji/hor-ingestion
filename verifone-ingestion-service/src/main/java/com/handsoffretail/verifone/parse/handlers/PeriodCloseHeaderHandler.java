package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;

/** trHeader of a periodClose: periodName, periodSeq, periodType, mwsEnabled (1 / 0). */
final class PeriodCloseHeaderHandler implements ElementHandler<HeaderDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("periodName", "periodSeq", "periodType", "mwsEnabled");
    }

    @Override
    public void handle(XmlElement e, HeaderDraft draft) {
        switch (e.name()) {
            case "periodName" -> draft.periodName = e.requiredText();
            case "periodSeq" -> draft.periodSeq = Values.intOf(e);
            case "periodType" -> draft.periodType = Values.intOf(e);
            default -> draft.mwsEnabled = Values.flag01(e.requiredText(), e.path());
        }
    }
}
