package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.XmlElement;

/** trJournal/trLine of an ERROR CORRECT event: the removed item, numbered from 1. */
final class EventLineHandler implements ElementHandler<EventDraft> {

    private final LineParser lines;

    EventLineHandler(LineParser lines) {
        this.lines = lines;
    }

    @Override
    public Set<String> elements() {
        return Set.of("trLine");
    }

    @Override
    public Set<String> repeatable() {
        return elements();
    }

    @Override
    public void handle(XmlElement e, EventDraft d) {
        d.lines.add(lines.parse(e, d.lines.size() + 1));
    }
}
