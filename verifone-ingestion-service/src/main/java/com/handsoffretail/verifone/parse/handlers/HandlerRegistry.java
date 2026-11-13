package com.handsoffretail.verifone.parse.handlers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.parse.UnmappedInputException;
import com.handsoffretail.verifone.parse.XmlElement;

/**
 * Sends each child of a container element to the handler registered for its name. A child no
 * handler maps fails the load (naming its path); so does a second occurrence of a child whose
 * handler does not declare it repeatable.
 */
public final class HandlerRegistry<D> {

    private final Map<String, ElementHandler<D>> byElement = new HashMap<>();

    public HandlerRegistry(List<? extends ElementHandler<D>> handlers) {
        for (ElementHandler<D> handler : handlers) {
            for (String element : handler.elements()) {
                if (byElement.put(element, handler) != null) {
                    throw new IllegalStateException("two handlers registered for <" + element + ">");
                }
            }
        }
    }

    public void dispatchChildren(XmlElement parent, D draft) {
        Map<String, Integer> seen = new HashMap<>();
        for (XmlElement child : parent.allChildren()) {
            ElementHandler<D> handler = byElement.get(child.name());
            if (handler == null) {
                throw new UnmappedInputException("element " + child.path());
            }
            int count = seen.merge(child.name(), 1, Integer::sum);
            if (count > 1 && !handler.repeatable().contains(child.name())) {
                throw new CpjFormatException(child.path() + " appears more than once where the loader expects one");
            }
            child.markUsed();
            handler.handle(child, draft);
        }
    }
}
