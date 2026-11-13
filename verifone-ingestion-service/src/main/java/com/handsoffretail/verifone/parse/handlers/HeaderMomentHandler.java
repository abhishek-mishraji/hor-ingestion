package com.handsoffretail.verifone.parse.handlers;

import java.util.Map;
import java.util.Set;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;

/**
 * trHeader date, duration and the three period levels. Each level's @name is the level's fixed
 * label (0 HOUR, 1 Shift, 2 Day on every sample record); any other label fails.
 */
final class HeaderMomentHandler implements ElementHandler<HeaderDraft> {

    private static final Map<Integer, String> LEVEL_NAMES = Map.of(0, "HOUR", 1, "Shift", 2, "Day");

    @Override
    public Set<String> elements() {
        return Set.of("date", "duration", "period");
    }

    @Override
    public Set<String> repeatable() {
        return Set.of("period");
    }

    @Override
    public void handle(XmlElement e, HeaderDraft draft) {
        switch (e.name()) {
            case "date" -> draft.at = Values.moment(e.requiredText(), e.path());
            case "duration" -> draft.durationSec = e.text().map(t -> Values.integer(t, e.path())).orElse(null);
            default -> period(e, draft);
        }
    }

    private static void period(XmlElement e, HeaderDraft draft) {
        int level = Values.requiredIntAttr(e, "level");
        String name = e.requiredAttr("name");
        int seq = Values.requiredIntAttr(e, "seq");
        if (!name.equals(LEVEL_NAMES.get(level))) {
            throw new CpjFormatException("unknown period level " + level + " named '" + name + "' at " + e.path());
        }
        switch (level) {
            case 0 -> draft.hourSeq = once(draft.hourSeq, seq, e);
            case 1 -> draft.shiftSeq = once(draft.shiftSeq, seq, e);
            default -> draft.daySeq = once(draft.daySeq, seq, e);
        }
    }

    private static Integer once(Integer current, int value, XmlElement e) {
        if (current != null) {
            throw new CpjFormatException("period level repeated at " + e.path());
        }
        return value;
    }
}
