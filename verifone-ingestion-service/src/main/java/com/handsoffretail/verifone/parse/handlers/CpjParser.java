package com.handsoffretail.verifone.parse.handlers;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.UnmappedInputException;
import com.handsoffretail.verifone.parse.Values;
import com.handsoffretail.verifone.parse.XmlElement;
import com.handsoffretail.verifone.parse.XmlElements;
import com.handsoffretail.verifone.parse.model.CpjFileHeader;
import com.handsoffretail.verifone.parse.model.CpjFileTrailer;
import com.handsoffretail.verifone.parse.model.CpjSink;
import com.handsoffretail.verifone.parse.model.FileTotals;
import com.handsoffretail.verifone.parse.model.ParseFacts;

/**
 * Streams one CPJ file (StAX, one {@code <trans>} in memory at a time) into a {@link CpjSink}.
 * {@code <trans>} are dispatched by @type to the registered {@link TransParser}s; an unknown type or
 * root element fails the load. {@code seq_in_file} counts every {@code <trans>}, journal included.
 */
public final class CpjParser {

    private static final String ROOT = "transSet";
    private static final XMLInputFactory FACTORY = XmlElements.secureFactory();

    private final Map<String, TransParser> byType;

    private CpjParser(Map<String, TransParser> byType) {
        this.byType = Map.copyOf(byType);
    }

    /** The parser with every element group mapped for Verifone CPJ. */
    public static CpjParser standard() {
        LineParser lines = LineParser.standard();
        Map<String, TransParser> byType = new HashMap<>();
        TicketParser tickets = new TicketParser(lines);
        TicketParser.TYPES.forEach(type -> byType.put(type, tickets));
        byType.put("journal", new JournalParser(lines));
        CashierTransParser cashier = new CashierTransParser();
        byType.put("openCashier", cashier);
        byType.put("closeCashier", cashier);
        byType.put("periodClose", new PeriodCloseParser());
        return new CpjParser(byType);
    }

    public ParseFacts parse(InputStream xml, CpjSink sink) {
        try {
            XMLStreamReader reader = FACTORY.createXMLStreamReader(xml);
            try {
                return parse(reader, sink);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new CpjFormatException("not well-formed XML: " + e.getMessage(), e);
        }
    }

    private ParseFacts parse(XMLStreamReader reader, CpjSink sink) throws XMLStreamException {
        while (reader.hasNext() && reader.next() != XMLStreamConstants.START_ELEMENT) {
            // move to the root element
        }
        if (!ROOT.equals(reader.getLocalName())) {
            throw new CpjFormatException("not a CPJ file: root element <" + reader.getLocalName() + ">");
        }
        Root root = new Root(XmlElements.attributes(reader));
        ParseState state = new ParseState(sink, root.site);
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                rootChild(XmlElements.read(reader, ROOT + "/" + reader.getLocalName()), root, state);
            }
        }
        if (root.endTotals == null) {
            throw new CpjFormatException("missing " + ROOT + "/endTotals");
        }
        root.begin(state);
        sink.end(new CpjFileTrailer(root.endTotals, state.sessions.sessions(), state.periodCloses));
        return state.facts();
    }

    private void rootChild(XmlElement e, Root root, ParseState state) {
        switch (e.name()) {
            case "openedTime" -> root.openedAt = Values.moment(e.requiredText(), e.path());
            case "closedTime" -> root.closedAt = Values.moment(e.requiredText(), e.path());
            case "startTotals" -> root.startTotals = totals(e, FileTotals.Kind.START);
            case "endTotals" -> root.endTotals = totals(e, FileTotals.Kind.END);
            case "openCashier", "closeCashier" -> {
                state.sessions.rootCopy(e);
                e.verifyFullyUsed();
            }
            case "trans" -> trans(e, root, state);
            default -> throw new UnmappedInputException("element " + e.path());
        }
    }

    private void trans(XmlElement raw, Root root, ParseState state) {
        root.begin(state);
        int seq = state.nextSeq();
        String type = raw.attr("type").orElseThrow(() -> new CpjFormatException("missing " + raw.path() + "/@type"));
        TransParser parser = byType.get(type);
        if (parser == null) {
            throw new CpjFormatException("unknown transaction type '" + type + "' at " + raw.path() + "/@type (#" + seq + ")");
        }
        parser.parse(raw, seq, state);
    }

    private static FileTotals totals(XmlElement e, FileTotals.Kind kind) {
        FileTotals totals = new FileTotals(kind, money(e, "insideSales"), money(e, "insideGrand"),
                money(e, "outsideSales"), money(e, "outsideGrand"), money(e, "attendantInsideSales"),
                money(e, "attendantInsideGrand"), money(e, "attendantOutsideSales"), money(e, "attendantOutsideGrand"),
                money(e, "overallSales"), money(e, "overallGrand"));
        e.verifyFullyUsed();
        return totals;
    }

    private static BigDecimal money(XmlElement e, String child) {
        return Values.requiredDecimal(e, child, Values.MONEY_SCALE);
    }

    /** transSet attributes and the values that must be known before the first ticket is written. */
    private static final class Root {
        private final String path = ROOT;
        final String site;
        final int periodId;
        final String periodName;
        final OffsetDateTime longId;
        final int shortId;
        OffsetDateTime openedAt;
        OffsetDateTime closedAt;
        FileTotals startTotals;
        FileTotals endTotals;
        private boolean begun;

        Root(Map<String, String> attributes) {
            Map<String, String> a = new HashMap<>(attributes);
            site = required(a, "site");
            periodId = Values.integer(required(a, "periodID"), path + "/@periodID");
            periodName = required(a, "periodname");
            longId = Values.moment(required(a, "longId"), path + "/@longId");
            shortId = Values.integer(required(a, "shortId"), path + "/@shortId");
            if (!a.isEmpty()) {
                throw new UnmappedInputException("attribute " + path + "/@" + a.keySet().iterator().next());
            }
        }

        private String required(Map<String, String> attributes, String name) {
            String value = attributes.remove(name);
            if (value == null || value.isBlank()) {
                throw new CpjFormatException("missing " + path + "/@" + name);
            }
            return value;
        }

        /** Emits the day header once, before the first trans (openedTime, closedTime, startTotals come first). */
        void begin(ParseState state) {
            if (begun) {
                return;
            }
            if (openedAt == null || closedAt == null || startTotals == null) {
                throw new CpjFormatException("openedTime, closedTime and startTotals must come before the first trans in " + path);
            }
            begun = true;
            state.sink.begin(new CpjFileHeader(periodId, periodName, longId, site, shortId, openedAt, closedAt, startTotals));
        }
    }
}
