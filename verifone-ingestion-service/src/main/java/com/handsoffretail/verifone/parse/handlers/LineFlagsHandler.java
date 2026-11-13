package com.handsoffretail.verifone.parse.handlers;

import java.util.HashSet;
import java.util.Set;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.model.Line;

/**
 * trlFlags: each child is one flag (its name is the code; only trlNegative has a @cause). Which
 * codes exist is checked against {@code cpj.line_flag_types} when the line is written.
 */
final class LineFlagsHandler implements ElementHandler<LineDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("trlFlags");
    }

    @Override
    public void handle(XmlElement e, LineDraft d) {
        Set<String> seen = new HashSet<>();
        for (XmlElement flag : e.allChildren()) {
            flag.markUsed();
            if (!seen.add(flag.name())) {
                throw new CpjFormatException("flag " + flag.path() + " appears twice");
            }
            d.flags.add(new Line.Flag(flag.name(), flag.attr("cause")));
        }
    }
}
