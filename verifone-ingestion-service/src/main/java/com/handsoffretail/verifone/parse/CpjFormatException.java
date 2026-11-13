package com.handsoffretail.verifone.parse;

/**
 * The CPJ file cannot be loaded as it is: a value the loader does not know, a missing required
 * part, two values that must agree but differ. The message names the element path and never
 * contains personal data (birth dates, card or loyalty numbers).
 */
public class CpjFormatException extends RuntimeException {

    public CpjFormatException(String message) {
        super(message);
    }

    public CpjFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
