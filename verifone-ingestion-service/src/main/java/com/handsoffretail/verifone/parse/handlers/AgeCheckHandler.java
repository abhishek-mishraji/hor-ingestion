package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;

/**
 * trValue/custDOB: @dob (MMDDYY), @custAge, @minAge, @dobEntryMeth. Its text repeats @dob (equal on
 * every sample record); a different text fails, so nothing is lost.
 */
final class AgeCheckHandler implements ElementHandler<ValueDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("custDOB");
    }

    @Override
    public void handle(XmlElement e, ValueDraft d) {
        d.ageCheck = read(e);
    }

    /** One custDOB element (also the copy in trHeader/customer, see {@link CustomerHandler}). */
    static ValueDraft.RawAgeCheck read(XmlElement e) {
        String dob = e.requiredAttr("dob");
        if (!e.text().map(dob::equals).orElse(true)) {
            throw new CpjFormatException("custDOB text differs from its @dob at " + e.path());
        }
        return new ValueDraft.RawAgeCheck(dob, Values.requiredIntAttr(e, "custAge"),
                Values.requiredIntAttr(e, "minAge"), e.requiredAttr("dobEntryMeth"), e.path());
    }
}
