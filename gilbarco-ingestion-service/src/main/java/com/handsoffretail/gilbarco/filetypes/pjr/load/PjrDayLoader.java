package com.handsoffretail.gilbarco.filetypes.pjr.load;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.springframework.stereotype.Component;

import com.handsoffretail.gilbarco.filetypes.FileLoadContext;
import com.handsoffretail.gilbarco.filetypes.LoadOutcome;
import com.handsoffretail.gilbarco.filetypes.pjr.model.PjrRows;
import com.handsoffretail.gilbarco.filetypes.pjr.parser.PjrFileName;
import com.handsoffretail.gilbarco.filetypes.pjr.parser.PjrFormatException;
import com.handsoffretail.gilbarco.filetypes.pjr.parser.PjrParser;
import com.handsoffretail.gilbarco.stores.StoreRef;
import com.handsoffretail.gilbarco.verify.Manifest;

/**
 * Loads a verified PJR day package (~700-1,050 files, one event each) inside the caller's load transaction:
 * parse every file first (a file the rules do not explain, or one whose BusinessDate is another day, fails the
 * whole day and is written to pjr.load_failure), then batch-insert files, events, lines, tenders and taxes, and
 * upsert the departments the day names.
 */
@Component
public class PjrDayLoader {

    private final JdbcPjrRepository repository;
    private final PjrLoadFailures failures;
    private final PjrParser parser = new PjrParser();

    PjrDayLoader(JdbcPjrRepository repository, PjrLoadFailures failures) {
        this.repository = repository;
        this.failures = failures;
    }

    public LoadOutcome load(FileLoadContext c) {
        List<Parsed> parsed = parseAll(c);
        StoreRef store = c.store();
        List<Long> ids = repository.nextFileIds(parsed.size());

        List<Object[]> files = new ArrayList<>(parsed.size());
        List<Object[]> events = new ArrayList<>(parsed.size());
        List<Object[]> lines = new ArrayList<>();
        List<Object[]> tenders = new ArrayList<>();
        List<Object[]> taxes = new ArrayList<>();
        Map<Integer, String> departments = new TreeMap<>();   // by number: concurrent loads of a store lock rows in one order
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (int i = 0; i < parsed.size(); i++) {
            long fileId = ids.get(i);
            Parsed p = parsed.get(i);
            PjrRows.ParsedFile f = p.rows();
            PjrRows.Event e = f.event();
            files.add(new Object[] {fileId, store.storeId(), store.storeCode(), c.s3ObjectId(), p.name().name(),
                p.file().sha256(), (int) p.file().size(), p.name().writtenAt().orElse(null), p.name().counter().orElse(null),
                f.storeLocation(), f.vendorVersion(), e.businessDate(), f.shiftBegin()});
            events.add(new Object[] {fileId, store.storeId(), store.storeCode(), e.businessDate(), e.eventType(),
                e.detailKind(), e.transactionId(), e.registerId(), e.cashierId(), e.tillId(), e.startedAt(), e.endedAt(),
                e.receiptAt(), e.outside(), e.suspended(), e.training(), e.offline(), e.linkedRegisterId(),
                e.linkedTransactionId(), e.linkReason(), e.loyaltyProgram(), e.totalGross(), e.totalNet(), e.totalTax(),
                e.totalGrand(), e.grandDirection(), e.detailAmount(), e.payee(), e.alertCode(), e.reason()});
            kinds.merge(e.detailKind(), 1, Integer::sum);
            for (PjrRows.Line l : f.lines()) {
                lines.add(new Object[] {fileId, l.lineNo(), store.storeId(), store.storeCode(), e.businessDate(), l.status(),
                    l.lineType(), l.deptNumber(), l.posCode(), l.posCodeFormat(), l.posCodeModifier(), l.description(),
                    l.entryMethod(), l.quantity(), l.sellingUnits(), l.actualPrice(), l.regularPrice(), l.salesAmount(),
                    l.promotionAmount(), l.discountAmount(), l.promotionReasons(), l.taxLevel(), l.fuelGradeId(),
                    l.fuelPosition(), l.priceTier(), l.serviceLevel()});
                boolean ring = l.lineType().equals("MerchandiseCodeLine");
                if (l.deptNumber() != null && (ring || l.lineType().equals("ItemLine"))) {
                    String name = ring ? l.description() : null;
                    departments.merge(l.deptNumber(), name == null ? "" : name, (a, b) -> b.isEmpty() ? a : b);
                }
            }
            for (PjrRows.Tender t : f.tenders()) {
                tenders.add(new Object[] {fileId, t.lineNo(), t.status(), t.tenderCode(), t.tenderSub(), t.amount(),
                    t.change(), t.provider(), t.authorizedAmount()});
            }
            for (PjrRows.Tax t : f.taxes()) {
                taxes.add(new Object[] {fileId, t.lineNo(), t.status(), t.taxLevel(), t.taxable(), t.collected(),
                    t.taxableRefunded(), t.taxRefunded(), t.exempt(), t.forgivenSales()});
            }
        }

        repository.insertFiles(files);
        repository.insertEvents(events);
        repository.insertLines(lines);
        repository.insertTenders(tenders);
        repository.insertTaxes(taxes);
        repository.upsertDepartments(departments.entrySet().stream().map(d -> {
            String name = d.getValue().isEmpty() ? null : d.getValue();
            return new Object[] {store.storeId(), store.storeCode(), d.getKey(), name, name, name};
        }).toList());

        Map<String, Long> rows = new LinkedHashMap<>();
        rows.put(JdbcPjrRepository.PJR_FILE, (long) files.size());
        rows.put(JdbcPjrRepository.EVENT, (long) events.size());
        rows.put(JdbcPjrRepository.EVENT_LINE, (long) lines.size());
        rows.put(JdbcPjrRepository.TENDER_LINE, (long) tenders.size());
        rows.put(JdbcPjrRepository.EVENT_TAX, (long) taxes.size());
        rows.put(JdbcPjrRepository.DEPARTMENTS, (long) departments.size());
        return new LoadOutcome(rows, "loaded " + files.size() + " PJR file(s): " + kinds + "; " + lines.size()
                + " lines, " + tenders.size() + " tenders, " + taxes.size() + " taxes, " + departments.size() + " departments");
    }

    /** Every file of the package, parsed and checked against the day, in path order. */
    private List<Parsed> parseAll(FileLoadContext c) {
        List<Parsed> parsed = new ArrayList<>(c.verified().files().size());
        try (ZipFile zip = c.verified().open()) {
            for (Manifest.File file : c.verified().files()) {
                parsed.add(parseOne(c, zip, file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the zip of " + c.businessDate(), e);
        }
        return parsed;
    }

    private Parsed parseOne(FileLoadContext c, ZipFile zip, Manifest.File file) throws IOException {
        String fileName = file.fileName();
        try {
            PjrFileName name = PjrFileName.of(fileName);
            ZipEntry entry = zip.getEntry(file.path());
            PjrRows.ParsedFile rows;
            try (InputStream in = new BufferedInputStream(zip.getInputStream(entry))) {
                rows = parser.parse(in, fileName);
            }
            if (!rows.event().businessDate().equals(c.businessDate())) {
                throw new PjrFormatException(fileName + ": BusinessDate " + rows.event().businessDate()
                        + " but the package is for " + c.businessDate());
            }
            return new Parsed(file, name, rows);
        } catch (PjrFormatException e) {
            failures.record(c.store().storeId(), c.store().storeCode(), c.fetchRequestId(), fileName, file.sha256(),
                    e.getMessage());
            throw e;
        }
    }

    private record Parsed(Manifest.File file, PjrFileName name, PjrRows.ParsedFile rows) {
    }
}
