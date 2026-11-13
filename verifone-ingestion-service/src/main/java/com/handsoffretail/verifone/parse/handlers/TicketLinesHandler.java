package com.handsoffretail.verifone.parse.handlers;

import java.util.List;
import java.util.Set;

import com.handsoffretail.verifone.parse.UnmappedInputException;
import com.handsoffretail.verifone.parse.XmlElement;

/** trLines: every trLine, numbered from 1 in document order. */
final class TicketLinesHandler implements ElementHandler<TicketDraft> {

    private final LineParser lines;

    TicketLinesHandler(LineParser lines) {
        this.lines = lines;
    }

    @Override
    public Set<String> elements() {
        return Set.of("trLines");
    }

    @Override
    public void handle(XmlElement e, TicketDraft d) {
        for (XmlElement child : e.allChildren()) {
            if (!child.name().equals("trLine")) {
                throw new UnmappedInputException("element " + child.path());
            }
        }
        List<XmlElement> trLines = e.children("trLine");
        for (int i = 0; i < trLines.size(); i++) {
            d.lines.add(lines.parse(trLines.get(i), i + 1));
        }
    }
}
