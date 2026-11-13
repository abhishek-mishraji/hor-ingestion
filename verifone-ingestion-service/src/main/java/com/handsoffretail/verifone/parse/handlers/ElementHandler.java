package com.handsoffretail.verifone.parse.handlers;

import java.util.Set;

import com.handsoffretail.verifone.parse.XmlElement;

/**
 * Maps one group of CPJ child elements (header, value, line, fuel, payment, tax, ...) into a draft.
 * A new CPJ element is supported by a new handler registered in a {@link HandlerRegistry}, not by
 * editing a switch (IMPLEMENTATION_PLAN.md §4, open/closed).
 *
 * @param <D> the draft being filled (ticket, header, line, ...)
 */
public interface ElementHandler<D> {

    /** The child element names this handler maps. */
    Set<String> elements();

    /** Names among {@link #elements()} that may occur more than once in the same parent. */
    default Set<String> repeatable() {
        return Set.of();
    }

    void handle(XmlElement element, D draft);
}
