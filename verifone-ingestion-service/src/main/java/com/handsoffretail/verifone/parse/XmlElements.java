package com.handsoffretail.verifone.parse;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Builds {@link XmlElement} trees from a StAX reader, with DTDs and external entities disabled. */
public final class XmlElements {

    private XmlElements() {
    }

    /** A StAX factory safe for untrusted input (no DTD, no external entities). */
    public static XMLInputFactory secureFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }

    /**
     * Reads the element the reader is positioned on (START_ELEMENT) and everything inside it.
     * {@code path} is this element's path for error messages.
     */
    public static XmlElement read(XMLStreamReader reader, String path) throws XMLStreamException {
        XmlElement element = new XmlElement(reader.getLocalName(), path, attributes(reader));
        while (reader.hasNext()) {
            switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT ->
                        element.addChild(read(reader, path + "/" + reader.getLocalName()));
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> element.appendText(reader.getText());
                case XMLStreamConstants.END_ELEMENT -> {
                    return element;
                }
                default -> {
                    // comments and processing instructions carry no data
                }
            }
        }
        throw new CpjFormatException("file ends inside " + path);
    }

    public static Map<String, String> attributes(XMLStreamReader reader) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            attributes.put(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
        }
        return attributes;
    }
}
