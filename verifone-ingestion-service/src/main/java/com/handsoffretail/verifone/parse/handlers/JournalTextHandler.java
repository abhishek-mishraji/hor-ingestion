package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.XmlElement;

/** trJournal/trjText (@type + the journal line) and the empty marker element. */
final class JournalTextHandler implements ElementHandler<EventDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("trjText", "marker");
    }

    @Override
    public void handle(XmlElement e, EventDraft d) {
        if (e.name().equals("marker")) {
            e.requireEmpty();
        } else {
            d.textType = e.requiredAttr("type");
            d.text = e.text().orElse("");
        }
    }
}
