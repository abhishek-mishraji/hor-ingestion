package com.handsoffretail.verifone.verify;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javax.xml.stream.XMLStreamException;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Hard: an independent second pass counts, from the XML, the trans per type (journal excluded),
 * trLine in tickets and in kept cashier events, trPayline, trpCardInfo, custDOB and the kept cashier
 * events; each count must equal the rows just stored.
 */
@Component
@Order(70)
class CountReconciliationCheck implements LoadCheck {

    private final CountReconciler reconciler;
    private final LoadCheckRepository repository;

    CountReconciliationCheck(CountReconciler reconciler, LoadCheckRepository repository) {
        this.reconciler = reconciler;
        this.repository = repository;
    }

    @Override
    public List<CheckResult> run(CheckContext c) {
        Map<String, Long> inXml = countXml(c);
        Map<String, Long> stored = repository.storedCounts(c.cpjFileId());
        TreeSet<String> names = new TreeSet<>(inXml.keySet());
        names.addAll(stored.keySet());
        List<CheckResult> results = new ArrayList<>();
        for (String name : names) {
            results.add(CheckResult.counted("count: " + name, inXml.getOrDefault(name, 0L), stored.getOrDefault(name, 0L),
                    true, "XML (second pass) vs rows stored"));
        }
        return results;
    }

    private Map<String, Long> countXml(CheckContext c) {
        try (InputStream xml = c.zip().open(c.entry())) {
            return reconciler.count(xml);
        } catch (IOException | XMLStreamException e) {
            throw new IllegalStateException("cannot read " + c.entry().path() + " for the count pass", e);
        }
    }
}
