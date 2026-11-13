package com.handsoffretail.gilbarco.filetypes.pjr.parser;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import com.handsoffretail.gilbarco.filetypes.pjr.model.PjrRows;

/**
 * Reads one PJR file (NAXML-POSJournal 3.4, exactly one event per file) into rows. Ported unchanged in its rules
 * from HOR-XML/pjr-platform, proven against Wolf's StoreClose PDFs. Files are small (under 25 KB), so a DOM parse
 * is used; the encoding (ISO-8859-1) comes from the XML declaration. DOCTYPEs and external entities are refused.
 * <p>
 * Anything not seen in the evidence (an unknown event, line, alert or detail kind, or more than one event) is a
 * {@link PjrFormatException}, so a day is never loaded half-understood. Card numbers, approval codes and loyalty
 * IDs are never read. Thread-safe: a new DocumentBuilder per parse.
 */
public class PjrParser {

    static final Set<String> EVENTS = Set.of("SaleEvent", "RefundEvent", "VoidEvent", "OtherEvent", "FinancialEvent");
    static final Set<String> SOLD_LINES = Set.of("ItemLine", "MerchandiseCodeLine", "FuelLine", "FuelPrepayLine");
    private static final String ROOT = "NAXML-POSJournal";

    private final DocumentBuilderFactory factory;

    public PjrParser() {
        factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @param fileName for the error message only
     * @throws PjrFormatException the file is not a PJR journal the rules explain
     */
    public PjrRows.ParsedFile parse(InputStream in, String fileName) {
        try {
            return parse(in);
        } catch (PjrFormatException e) {
            throw new PjrFormatException(fileName + ": " + e.getMessage(), e);
        } catch (IOException | SAXException e) {
            throw new PjrFormatException(fileName + ": not valid XML: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // a malformed number, date or time
            throw new PjrFormatException(fileName + ": " + e.getClass().getSimpleName() + " " + e.getMessage(), e);
        }
    }

    private PjrRows.ParsedFile parse(InputStream in) throws IOException, SAXException {
        Element root;
        try {
            DocumentBuilder b = factory.newDocumentBuilder();
            root = b.parse(in).getDocumentElement();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        if (!ROOT.equals(root.getTagName())) {
            throw new PjrFormatException("not a PJR journal: root element is " + root.getTagName());
        }
        Element header = child(root, "TransmissionHeader");
        Element report = child(root, "JournalReport");
        if (report == null) {
            throw new PjrFormatException("no JournalReport element");
        }
        Element jh = child(report, "JournalHeader");

        List<Element> events = new ArrayList<>();
        for (Element e : children(report)) {
            if (!e.getTagName().equals("JournalHeader")) {
                events.add(e);
            }
        }
        if (events.size() != 1) {
            throw new PjrFormatException("expected exactly one event, found " + events.size());
        }
        Element ev = events.get(0);
        if (!EVENTS.contains(ev.getTagName())) {
            throw new PjrFormatException("unknown event type " + ev.getTagName());
        }

        List<PjrRows.Line> lines = new ArrayList<>();
        List<PjrRows.Tender> tenders = new ArrayList<>();
        List<PjrRows.Tax> taxes = new ArrayList<>();
        readLines(ev, lines, tenders, taxes);

        return new PjrRows.ParsedFile(text(header, "StoreLocationID"), text(header, "VendorModelVersion"),
                dateTime(text(jh, "BeginDate"), text(jh, "BeginTime")), readEvent(ev), lines, tenders, taxes);
    }

    private static PjrRows.Event readEvent(Element ev) {
        String type = ev.getTagName();
        String bd = text(ev, "BusinessDate");
        if (bd == null) {
            throw new PjrFormatException(type + " has no BusinessDate");
        }

        String kind;
        BigDecimal detailAmount = null;
        String payee = null;
        String alertCode = null;
        String reason = null;
        switch (type) {
            case "SaleEvent" -> kind = "sale";
            case "RefundEvent" -> {
                kind = "refund";
                reason = text(ev, "RefundReason");
            }
            case "VoidEvent" -> {
                kind = "void";
                reason = text(ev, "VoidReason");
            }
            case "OtherEvent" -> {
                Element alert = child(ev, "AlertDetail");
                Element reg = child(ev, "RegisterDetail");
                if (alert != null) {
                    alertCode = text(alert, "AlertCode");
                    if (!"NoSale".equals(text(alert, "Description")) && !"CashDrawerOpen".equals(alertCode)) {
                        throw new PjrFormatException("unknown alert: " + text(alert, "Description") + " / " + alertCode);
                    }
                    kind = "no_sale";
                } else if (reg != null) {
                    String dt = reg.getAttribute("detailType");
                    if (!dt.equals("open") && !dt.equals("close")) {
                        throw new PjrFormatException("unknown RegisterDetail detailType " + dt);
                    }
                    kind = "register_" + dt;
                    detailAmount = num(text(reg, "CashInDrawer"));
                } else {
                    throw new PjrFormatException("OtherEvent without AlertDetail or RegisterDetail");
                }
            }
            case "FinancialEvent" -> {
                Element fd = child(ev, "FinancialEventDetail");
                Element drop = fd == null ? null : child(fd, "SafeDropDetail");
                Element pay = fd == null ? null : child(fd, "PayOutDetail");
                Element payIn = fd == null ? null : child(fd, "PayInDetail");
                if (drop != null) {
                    kind = "safe_drop";
                    detailAmount = num(text(drop, "DropAmount"));
                } else if (pay != null) {
                    kind = "payout";
                    detailAmount = num(text(pay, "DetailAmount"));
                    payee = text(pay, "AccountInfo/AccountName");   // the vendor paid, not a card
                    reason = text(pay, "PayOutReason");
                } else if (payIn != null) {
                    // Seen from October 2026 (2 of 10,514 files): cash paid into the drawer. Never a sale. Its
                    // AccountInfo may name a person, so only the amount and the reason are read (owner, 2026-10-23).
                    kind = "pay_in";
                    detailAmount = num(text(payIn, "DetailAmount"));
                    reason = text(payIn, "PayInReason");
                } else {
                    throw new PjrFormatException("FinancialEvent without SafeDropDetail, PayOutDetail or PayInDetail");
                }
            }
            default -> throw new PjrFormatException("unknown event type " + type);
        }

        Element sum = child(ev, "TransactionSummary");
        Element grand = sum == null ? null : child(sum, "TransactionTotalGrandAmount");
        return new PjrRows.Event(type, kind, LocalDate.parse(bd), lng(text(ev, "TransactionID")),
                integer(text(ev, "RegisterID")), text(ev, "CashierID"), text(ev, "TillID"),
                dateTime(text(ev, "EventStartDate"), text(ev, "EventStartTime")),
                dateTime(text(ev, "EventEndDate"), text(ev, "EventEndTime")),
                dateTime(text(ev, "ReceiptDate"), text(ev, "ReceiptTime")),
                flag(ev, "OutsideSalesFlag"), flag(ev, "SuspendFlag"), flag(ev, "TrainingModeFlag"), flag(ev, "OfflineFlag"),
                integer(text(ev, "LinkedTransactionInfo/OriginalRegisterID")),
                lng(text(ev, "LinkedTransactionInfo/OriginalTransactionID")),
                text(ev, "LinkedTransactionInfo/TransactionLinkReason"),
                text(ev, "Extension/LoyaltyInfo/LoyaltyProgramName"),
                num(text(sum, "TransactionTotalGrossAmount")), num(text(sum, "TransactionTotalNetAmount")),
                num(text(sum, "TransactionTotalTaxNetAmount")), num(text(sum, "TransactionTotalGrandAmount")),
                grand == null || grand.getAttribute("direction").isEmpty() ? null : grand.getAttribute("direction"),
                detailAmount, payee, alertCode, reason);
    }

    private static void readLines(Element ev, List<PjrRows.Line> lines, List<PjrRows.Tender> tenders,
            List<PjrRows.Tax> taxes) {
        Element group = child(ev, "TransactionDetailGroup");
        if (group == null) {
            return;
        }
        int lineNo = 0;
        for (Element tl : children(group)) {
            if (!tl.getTagName().equals("TransactionLine")) {
                throw new PjrFormatException("unknown element in TransactionDetailGroup: " + tl.getTagName());
            }
            lineNo++;
            String status = tl.getAttribute("status");
            List<Element> kids = children(tl);
            if (kids.size() != 1) {
                throw new PjrFormatException("TransactionLine " + lineNo + " has " + kids.size() + " child elements");
            }
            Element k = kids.get(0);
            String kind = k.getTagName();
            if (SOLD_LINES.contains(kind)) {
                lines.add(readLine(lineNo, status, k));
            } else if (kind.equals("TenderInfo")) {
                Element cf = child(k, "ChangeFlag");
                tenders.add(new PjrRows.Tender(lineNo, status, text(k, "Tender/TenderCode"), text(k, "Tender/TenderSubCode"),
                        numOrZero(text(k, "TenderAmount")), cf != null && "yes".equals(cf.getAttribute("value")),
                        text(k, "Authorization/ProviderID"), num(text(k, "Authorization/AuthorizedChargeAmount"))));
            } else if (kind.equals("TransactionTax")) {
                taxes.add(new PjrRows.Tax(lineNo, status, text(k, "TaxLevelID"), num(text(k, "TaxableSalesAmount")),
                        num(text(k, "TaxCollectedAmount")), num(text(k, "TaxableSalesRefundedAmount")),
                        num(text(k, "TaxRefundedAmount")), num(text(k, "TaxExemptSalesAmount")),
                        num(text(k, "TaxForgivenSalesAmount"))));
            } else {
                throw new PjrFormatException("unknown line type " + kind + " (TransactionLine " + lineNo + ")");
            }
        }
    }

    private static PjrRows.Line readLine(int lineNo, String status, Element k) {
        BigDecimal promo = BigDecimal.ZERO;
        BigDecimal disc = BigDecimal.ZERO;
        Set<String> reasons = new LinkedHashSet<>();
        for (Element p : descendants(k, "Promotion")) {
            promo = promo.add(numOrZero(text(p, "PromotionAmount")));
            if (text(p, "PromotionReason") != null) {
                reasons.add(text(p, "PromotionReason"));
            }
        }
        for (Element d : descendants(k, "Discount")) {
            disc = disc.add(numOrZero(text(d, "DiscountAmount")));
        }
        Element entry = child(k, "EntryMethod");
        Element fmt = child(k, "ItemCode/POSCodeFormat");
        return new PjrRows.Line(lineNo, status, k.getTagName(), integer(text(k, "MerchandiseCode")),
                text(k, "ItemCode/POSCode"), fmt == null || fmt.getAttribute("format").isEmpty() ? null : fmt.getAttribute("format"),
                text(k, "ItemCode/POSCodeModifier"), text(k, "Description"),
                entry == null || entry.getAttribute("method").isEmpty() ? null : entry.getAttribute("method"),
                num(text(k, "SalesQuantity")), num(text(k, "SellingUnits")), num(text(k, "ActualSalesPrice")),
                num(text(k, "RegularSellPrice")), numOrZero(text(k, "SalesAmount")), promo, disc,
                reasons.isEmpty() ? null : String.join(",", reasons), text(k, "ItemTax/TaxLevelID"),
                text(k, "FuelGradeID"), integer(text(k, "FuelPositionID")), text(k, "PriceTierCode"),
                text(k, "ServiceLevelCode"));
    }

    // ------------------------------------------------------------------ DOM helpers

    private static List<Element> children(Element e) {
        List<Element> out = new ArrayList<>();
        if (e == null) {
            return out;
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c) {
                out.add(c);
            }
        }
        return out;
    }

    /** Child element by a slash path of tag names, or null. */
    static Element child(Element e, String path) {
        Element cur = e;
        for (String part : path.split("/")) {
            if (cur == null) {
                return null;
            }
            Element next = null;
            for (Element c : children(cur)) {
                if (c.getTagName().equals(part)) {
                    next = c;
                    break;
                }
            }
            cur = next;
        }
        return cur;
    }

    private static List<Element> descendants(Element e, String tag) {
        List<Element> out = new ArrayList<>();
        var nl = e.getElementsByTagName(tag);
        for (int i = 0; i < nl.getLength(); i++) {
            out.add((Element) nl.item(i));
        }
        return out;
    }

    /** Trimmed text of a child path; null when missing or empty. */
    static String text(Element e, String path) {
        Element c = e == null ? null : child(e, path);
        if (c == null) {
            return null;
        }
        String t = c.getTextContent().trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean flag(Element ev, String name) {
        Element f = child(ev, name);
        return f != null && "yes".equals(f.getAttribute("value"));
    }

    private static BigDecimal num(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    private static BigDecimal numOrZero(String s) {
        return s == null ? BigDecimal.ZERO : new BigDecimal(s);
    }

    private static Integer integer(String s) {
        return s == null ? null : Integer.valueOf(s);
    }

    private static Long lng(String s) {
        return s == null ? null : Long.valueOf(s);
    }

    private static LocalDateTime dateTime(String date, String time) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.of(LocalDate.parse(date), time == null ? LocalTime.MIDNIGHT : LocalTime.parse(time));
    }
}
