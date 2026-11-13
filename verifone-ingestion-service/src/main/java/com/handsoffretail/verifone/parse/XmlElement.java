package com.handsoffretail.verifone.parse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One XML element and everything inside it, built for a single {@code <trans>} (or root element)
 * at a time. Every read marks what was read; {@link #verifyFullyUsed()} then fails on anything the
 * mapping did not read, naming its path. Reading a repeated element as if it occurred once fails
 * too, so a second occurrence can never be dropped silently.
 */
public final class XmlElement {

    private final String name;
    private final String path;
    private final Map<String, String> attributes;
    private final List<XmlElement> children = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private final Set<String> usedAttributes = new HashSet<>();
    private boolean used;
    private boolean textUsed;

    XmlElement(String name, String path, Map<String, String> attributes) {
        this.name = name;
        this.path = path;
        this.attributes = attributes.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    void addChild(XmlElement child) {
        children.add(child);
    }

    void appendText(String characters) {
        text.append(characters);
    }

    public String name() {
        return name;
    }

    public String path() {
        return path;
    }

    // ---- attributes -------------------------------------------------------------------------

    public Optional<String> attr(String attribute) {
        usedAttributes.add(attribute);
        return Optional.ofNullable(attributes.get(attribute));
    }

    /** The names of all attributes (reading them still goes through {@link #attr}). */
    public Set<String> attributeNames() {
        return attributes.keySet();
    }

    public String requiredAttr(String attribute) {
        return attr(attribute).orElseThrow(() -> new CpjFormatException("missing " + path + "/@" + attribute));
    }

    // ---- children ---------------------------------------------------------------------------

    /** The child with this name, if present. More than one fails: it would hide data. */
    public Optional<XmlElement> child(String childName) {
        List<XmlElement> found = children(childName);
        if (found.size() > 1) {
            throw new CpjFormatException(path + "/" + childName + " appears " + found.size()
                    + " times where the loader expects at most one");
        }
        return found.stream().findFirst();
    }

    public XmlElement requiredChild(String childName) {
        return child(childName).orElseThrow(() -> new CpjFormatException("missing " + path + "/" + childName));
    }

    /** Every child with this name, in document order (marked as read). */
    public List<XmlElement> children(String childName) {
        List<XmlElement> found = new ArrayList<>();
        for (XmlElement child : children) {
            if (child.name.equals(childName)) {
                child.used = true;
                found.add(child);
            }
        }
        return found;
    }

    /** All children in document order, for handler dispatch (each is marked when a handler reads it). */
    public List<XmlElement> allChildren() {
        return Collections.unmodifiableList(children);
    }

    // ---- text -------------------------------------------------------------------------------

    /** The trimmed text, if not empty. */
    public Optional<String> text() {
        textUsed = true;
        String value = text.toString().strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    public String requiredText() {
        return text().orElseThrow(() -> new CpjFormatException("empty " + path));
    }

    /** Text of a child that occurs at most once; empty when the child is absent or empty. */
    public Optional<String> childText(String childName) {
        return child(childName).flatMap(XmlElement::text);
    }

    public String requiredChildText(String childName) {
        return requiredChild(childName).requiredText();
    }

    // ---- consumption ------------------------------------------------------------------------

    /** Marks this element as read (for elements whose presence is the value, e.g. trFuelOnlyCst). */
    public void markUsed() {
        used = true;
    }

    /** An element that must be empty: no attributes, children or text. */
    public void requireEmpty() {
        used = true;
        if (!attributes.isEmpty() || !children.isEmpty() || !text.toString().isBlank()) {
            throw new UnmappedInputException("content in " + path + " (expected an empty element)");
        }
    }

    /**
     * Marks this element and its children as read, after checking that only the given child names
     * occur and that those children hold no elements themselves. For journal text the owner decided
     * not to store (skipped by design): a known shape is skipped, anything else still fails.
     */
    public void skipKnown(Set<String> allowedChildren) {
        used = true;
        textUsed = true;
        usedAttributes.addAll(attributes.keySet());
        for (XmlElement child : children) {
            if (!allowedChildren.contains(child.name)) {
                throw new UnmappedInputException("element " + child.path);
            }
            child.skipKnown(Set.of());
        }
    }

    /** Fails on the first element, attribute or text that no mapping read. */
    public void verifyFullyUsed() {
        for (String attribute : attributes.keySet()) {
            if (!usedAttributes.contains(attribute)) {
                throw new UnmappedInputException("attribute " + path + "/@" + attribute);
            }
        }
        if (!textUsed && !text.toString().isBlank()) {
            throw new UnmappedInputException("text in " + path);
        }
        for (XmlElement child : children) {
            if (!child.used) {
                throw new UnmappedInputException("element " + child.path);
            }
            child.verifyFullyUsed();
        }
    }
}
