package com.handsoffretail.verifone.verify;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.springframework.stereotype.Component;

/**
 * The second, independent pass over a CPJ file: plain element counting with its own small state
 * machine, sharing no code with the main parser, so anything the parser dropped shows up as a
 * difference from the rows stored.
 */
@Component
public class CountReconciler {

    public static final String TICKET_LINES = "trLine in tickets";
    public static final String EVENT_LINES = "trLine in kept cashier events";
    public static final String PAYLINES = "trPayline";
    public static final String CARD_INFOS = "trpCardInfo";
    public static final String AGE_CHECKS = "custDOB";
    public static final String KEPT_EVENTS = "kept cashier events";
    private static final Set<String> KEPT_KINDS = Set.of("CHANGE QTY", "ERROR CORRECT", "LOGIN/LOGOUT",
            "SAFE DROP EVENT", "PAY OUT EVENT", "PRICE CHECK");
    private static final int TRANS_DEPTH = 2;

    private final XMLInputFactory factory;

    public CountReconciler() {
        factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    /** Counts from the XML, under the same names as {@link LoadCheckRepository#storedCounts}. */
    public Map<String, Long> count(InputStream xml) throws XMLStreamException {
        Map<String, Long> counts = new TreeMap<>();
        XMLStreamReader r = factory.createXMLStreamReader(xml);
        try {
            int depth = 0;
            String transType = null;
            String journalKind = null;
            long linesInTrans = 0;
            int customerDepth = 0;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String name = r.getLocalName();
                    if (depth == TRANS_DEPTH && name.equals("trans")) {
                        transType = r.getAttributeValue(null, "type");
                        journalKind = null;
                        linesInTrans = 0;
                    } else if (transType != null) {
                        switch (name) {
                            case "trLine" -> linesInTrans++;
                            case "trjText" -> journalKind = r.getAttributeValue(null, "type");
                            case "trPayline" -> add(counts, PAYLINES);
                            case "trpCardInfo" -> add(counts, CARD_INFOS);
                            // trHeader/customer/custDOB repeats the ticket's trValue/custDOB: one age check
                            case "customer" -> customerDepth = depth;
                            case "custDOB" -> {
                                if (customerDepth == 0) {
                                    add(counts, AGE_CHECKS);
                                }
                            }
                            default -> {
                                // other elements are not counted
                            }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (depth == customerDepth) {
                        customerDepth = 0;
                    }
                    if (depth == TRANS_DEPTH && transType != null) {
                        endTrans(counts, transType, journalKind, linesInTrans);
                        transType = null;
                    }
                    depth--;
                }
            }
        } finally {
            r.close();
        }
        return counts;
    }

    private static void endTrans(Map<String, Long> counts, String type, String journalKind, long lines) {
        if (type.equals("journal")) {
            if (KEPT_KINDS.contains(journalKind)) {
                add(counts, KEPT_EVENTS);
                counts.merge(EVENT_LINES, lines, Long::sum);
            }
            return;
        }
        add(counts, "trans[" + type + "]");
        counts.merge(TICKET_LINES, lines, Long::sum);
    }

    private static void add(Map<String, Long> counts, String name) {
        counts.merge(name, 1L, Long::sum);
    }
}
