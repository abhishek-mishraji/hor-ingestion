package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.UnmappedInputException;
import com.handsoffretail.verifone.parse.XmlElement;

/**
 * trHeader/customer (seen on store 1409_UT-84790): holds only custDOB, a copy of the ticket's
 * trValue/custDOB. Nothing is stored from it; {@link TicketParser} requires the two to be equal.
 */
final class CustomerHandler implements ElementHandler<HeaderDraft> {

    @Override
    public Set<String> elements() {
        return Set.of("customer");
    }

    @Override
    public void handle(XmlElement e, HeaderDraft draft) {
        for (XmlElement child : e.allChildren()) {
            if (!child.name().equals("custDOB")) {
                throw new UnmappedInputException("element " + child.path());
            }
        }
        draft.customerAgeCheck = AgeCheckHandler.read(e.requiredChild("custDOB"));
    }
}
