package com.handsoffretail.verifone.parse.handlers;

import com.handsoffretail.verifone.parse.XmlElement;

/** Maps one {@code <trans>} of the types it is registered for. */
interface TransParser {

    void parse(XmlElement trans, int seqInFile, ParseState state);
}
