package com.handsoffretail.verifone.parse;

/**
 * The file contains an element, attribute or text the loader has no mapping for. By design this
 * fails the load (CLAUDE.md: unknown POS input fails the load, naming the element); the fix is to
 * map it, never to skip it.
 */
public class UnmappedInputException extends CpjFormatException {

    public UnmappedInputException(String what) {
        super("unmapped " + what);
    }
}
