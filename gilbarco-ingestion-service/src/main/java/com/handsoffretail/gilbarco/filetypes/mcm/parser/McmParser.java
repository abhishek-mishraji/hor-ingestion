package com.handsoffretail.gilbarco.filetypes.mcm.parser;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Reads one MCM file (NAXML-MovementReport 3.4, MerchandiseCodeMovement). Passport writes two per close: period 98
 * (one block per register / till) and period 2 (the whole store, one block). Only period 2 is read into
 * departments; period 98 is read for its header only. Shape proven on every Wolf MCM file of 2026-10-09..26
 * (34 files): every MCMSalesTotals element below is always present, so a missing one is a format error.
 * DOCTYPEs and external entities are refused. Thread-safe: a new DocumentBuilder per parse.
 */
public class McmParser {

    private static final String ROOT = "NAXML-MovementReport";
    private static final String MOVEMENT = "MerchandiseCodeMovement";

    private final DocumentBuilderFactory factory;

    public McmParser() {
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
     * @throws McmFormatException the file is not an MCM report the rules explain
     */
    public McmReport parse(InputStream in, String fileName) {
        try {
            return parse(in);
        } catch (McmFormatException e) {
            throw new McmFormatException(fileName + ": " + e.getMessage(), e);
        } catch (IOException | SAXException e) {
            throw new McmFormatException(fileName + ": not valid XML: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // a malformed number, date or time
            throw new McmFormatException(fileName + ": " + e.getClass().getSimpleName() + " " + e.getMessage(), e);
        }
    }

    private McmReport parse(InputStream in) throws IOException, SAXException {
        Element root;
        try {
            DocumentBuilder b = factory.newDocumentBuilder();
            root = b.parse(in).getDocumentElement();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        if (!ROOT.equals(root.getTagName())) {
            throw new McmFormatException("not a movement report: root element is " + root.getTagName());
        }
        Element header = child(root, "TransmissionHeader");
        List<Element> movements = children(root, MOVEMENT);
        if (movements.isEmpty()) {
            throw new McmFormatException("no " + MOVEMENT + " element (not an MCM report)");
        }

        Element mh = required(movements.get(0), "MovementHeader");
        int period = Integer.parseInt(requiredText(mh, "PrimaryReportPeriod"));
        LocalDate businessDate = LocalDate.parse(requiredText(mh, "BusinessDate"));
        LocalDateTime begin = LocalDateTime.of(LocalDate.parse(requiredText(mh, "BeginDate")),
                LocalTime.parse(requiredText(mh, "BeginTime")));
        LocalDateTime end = LocalDateTime.of(LocalDate.parse(requiredText(mh, "EndDate")),
                LocalTime.parse(requiredText(mh, "EndTime")));

        List<McmReport.Department> departments = new ArrayList<>();
        if (period == McmReport.STORE_PERIOD) {
            if (movements.size() != 1) {
                throw new McmFormatException("store-level report (period 2) has " + movements.size() + " "
                        + MOVEMENT + " blocks, expected 1");
            }
            Set<Integer> seen = new HashSet<>();
            for (Element d : children(movements.get(0), "MCMDetail")) {
                McmReport.Department dept = department(d);
                if (!seen.add(dept.deptNumber())) {
                    throw new McmFormatException("MerchandiseCode " + dept.deptNumber() + " listed twice");
                }
                departments.add(dept);
            }
        } else if (period != McmReport.REGISTER_PERIOD) {
            throw new McmFormatException("unknown PrimaryReportPeriod " + period + " (expected 2 or 98)");
        }
        return new McmReport(period, businessDate, begin, end, text(header, "StoreLocationID"),
                text(header, "VendorModelVersion"), departments);
    }

    private static McmReport.Department department(Element d) {
        int number = Integer.parseInt(requiredText(d, "MerchandiseCode"));
        Element t = required(d, "MCMSalesTotals");
        String name = text(d, "MerchandiseCodeDescription");
        return new McmReport.Department(number, name == null || name.isBlank() ? null : name,
                amount(t, "SalesAmount"), amount(t, "SalesQuantity"), amount(t, "PromotionAmount"),
                count(t, "PromotionCount"), amount(t, "DiscountAmount"), count(t, "DiscountCount"),
                amount(t, "RefundAmount"), count(t, "RefundCount"), count(t, "TransactionCount"),
                amount(t, "OpenDepartmentSalesAmount"), count(t, "OpenDepartmentTransactionCount"));
    }

    /** Passport writes "-.74" and ".1"; BigDecimal reads both. */
    private static BigDecimal amount(Element parent, String tag) {
        return new BigDecimal(requiredText(parent, tag));
    }

    private static int count(Element parent, String tag) {
        return Integer.parseInt(requiredText(parent, tag));
    }

    private static Element required(Element parent, String tag) {
        Element e = child(parent, tag);
        if (e == null) {
            throw new McmFormatException("no " + tag + " in " + parent.getTagName());
        }
        return e;
    }

    private static String requiredText(Element parent, String tag) {
        String value = text(parent, tag);
        if (value == null || value.isBlank()) {
            throw new McmFormatException("no " + tag + " in " + parent.getTagName());
        }
        return value;
    }

    private static String text(Element parent, String tag) {
        Element e = parent == null ? null : child(parent, tag);
        return e == null ? null : e.getTextContent().strip();
    }

    private static Element child(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) {
                out.add(e);
            }
        }
        return out;
    }
}
