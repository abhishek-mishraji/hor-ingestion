package com.handsoffretail.verifone.parse;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Converts CPJ text to typed values, failing with the element path when a value does not fit
 * (never rounding: a decimal with more places than its column keeps is refused).
 */
public final class Values {

    /** numeric(…,2) columns: money. */
    public static final int MONEY_SCALE = 2;
    /** numeric(…,3) columns: quantities, unit prices, rates, fuel prices. */
    public static final int QUANTITY_SCALE = 3;

    private Values() {
    }

    public static int integer(String text, String path) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            throw new CpjFormatException("not a whole number in " + path, e);
        }
    }

    public static long longValue(String text, String path) {
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            throw new CpjFormatException("not a whole number in " + path, e);
        }
    }

    public static BigDecimal decimal(String text, int maxScale, String path) {
        BigDecimal value;
        try {
            value = new BigDecimal(text.strip());
        } catch (NumberFormatException e) {
            throw new CpjFormatException("not a number in " + path, e);
        }
        if (value.stripTrailingZeros().scale() > maxScale) {
            throw new CpjFormatException("more than " + maxScale + " decimal places in " + path);
        }
        return value;
    }

    public static OffsetDateTime moment(String text, String path) {
        try {
            return OffsetDateTime.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw new CpjFormatException("not a date-time with offset in " + path, e);
        }
    }

    /** {@code true} / {@code false}; anything else fails. */
    public static boolean bool(String text, String path) {
        return switch (text.strip()) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new CpjFormatException("expected true or false in " + path);
        };
    }

    /** {@code 1} / {@code 0}; anything else fails. */
    public static boolean flag01(String text, String path) {
        return switch (text.strip()) {
            case "1" -> true;
            case "0" -> false;
            default -> throw new CpjFormatException("expected 1 or 0 in " + path);
        };
    }

    // ---- element shortcuts ------------------------------------------------------------------

    public static int intOf(XmlElement element) {
        return integer(element.requiredText(), element.path());
    }

    public static Optional<Integer> optionalInt(XmlElement parent, String child) {
        return parent.child(child).flatMap(e -> e.text().map(t -> integer(t, e.path())));
    }

    public static Optional<Long> optionalLong(XmlElement parent, String child) {
        return parent.child(child).flatMap(e -> e.text().map(t -> longValue(t, e.path())));
    }

    public static BigDecimal requiredDecimal(XmlElement parent, String child, int scale) {
        XmlElement element = parent.requiredChild(child);
        return decimal(element.requiredText(), scale, element.path());
    }

    public static Optional<BigDecimal> optionalDecimal(XmlElement parent, String child, int scale) {
        return parent.child(child).flatMap(e -> e.text().map(t -> decimal(t, scale, e.path())));
    }

    public static int requiredIntAttr(XmlElement element, String attribute) {
        return integer(element.requiredAttr(attribute), element.path() + "/@" + attribute);
    }

    public static Optional<Integer> optionalIntAttr(XmlElement element, String attribute) {
        return element.attr(attribute).filter(v -> !v.isBlank())
                .map(v -> integer(v, element.path() + "/@" + attribute));
    }

    /** A {@code true}/{@code false} attribute; absent means false. */
    public static boolean boolAttr(XmlElement element, String attribute) {
        return element.attr(attribute).map(v -> bool(v, element.path() + "/@" + attribute)).orElse(false);
    }
}
