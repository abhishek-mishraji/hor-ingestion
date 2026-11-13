package com.handsoffretail.gilbarco.filetypes.mcm.load;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import org.springframework.stereotype.Component;

import com.handsoffretail.gilbarco.filetypes.FileLoadContext;
import com.handsoffretail.gilbarco.filetypes.LoadOutcome;
import com.handsoffretail.gilbarco.filetypes.mcm.parser.McmFormatException;
import com.handsoffretail.gilbarco.filetypes.mcm.parser.McmParser;
import com.handsoffretail.gilbarco.filetypes.mcm.parser.McmReport;
import com.handsoffretail.gilbarco.stores.StoreRef;
import com.handsoffretail.gilbarco.verify.Manifest;

/**
 * Loads a verified MCM day package (two files: period 98 per register, period 2 for the store) inside the caller's
 * load transaction. Every file is parsed and must be of the package's business day; exactly one must be the
 * store-level file. Its departments go to mcm.department_day, and their names to master.departments.
 * A file the rules do not explain fails the whole day (the FAILED fetch message names it).
 */
@Component
public class McmDayLoader {

    private final JdbcMcmRepository repository;
    private final McmParser parser = new McmParser();

    McmDayLoader(JdbcMcmRepository repository) {
        this.repository = repository;
    }

    public LoadOutcome load(FileLoadContext c) {
        Parsed store = storeFile(c);
        StoreRef s = c.store();
        McmReport r = store.report();
        List<McmReport.Department> departments = r.departments().stream()
                .sorted(Comparator.comparingInt(McmReport.Department::deptNumber))   // one lock order for concurrent loads
                .toList();

        List<Object[]> days = new ArrayList<>(departments.size());
        List<Object[]> names = new ArrayList<>(departments.size());
        BigDecimal sales = BigDecimal.ZERO;
        for (McmReport.Department d : departments) {
            days.add(new Object[] {s.storeId(), s.storeCode(), r.businessDate(), d.deptNumber(), d.deptName(),
                d.salesAmount(), d.salesQuantity(), d.promotionAmount(), d.promotionCount(), d.discountAmount(),
                d.discountCount(), d.refundAmount(), d.refundCount(), d.transactionCount(), d.openDeptSalesAmount(),
                d.openDeptTransactionCount(), c.s3ObjectId(), store.file().fileName(), store.file().sha256(),
                r.periodBegin(), r.periodEnd()});
            if (d.deptName() != null) {
                names.add(new Object[] {s.storeId(), s.storeCode(), d.deptNumber(), d.deptName(), d.deptName(), d.deptName()});
            }
            sales = sales.add(d.salesAmount());
        }
        repository.insertDepartmentDays(days);
        repository.upsertDepartments(names);

        Map<String, Long> rows = new LinkedHashMap<>();
        rows.put(JdbcMcmRepository.DEPARTMENT_DAY, (long) days.size());
        rows.put(JdbcMcmRepository.DEPARTMENTS, (long) names.size());
        return new LoadOutcome(rows, "loaded MCM " + store.file().fileName() + ": " + days.size() + " departments ("
                + names.size() + " named), sales " + sales.toPlainString());
    }

    /** Parses every file of the package; returns the one store-level file. */
    private Parsed storeFile(FileLoadContext c) {
        List<Parsed> stores = new ArrayList<>(1);
        try (ZipFile zip = c.verified().open()) {
            for (Manifest.File file : c.verified().files()) {
                McmReport report;
                try (InputStream in = new BufferedInputStream(zip.getInputStream(zip.getEntry(file.path())))) {
                    report = parser.parse(in, file.fileName());
                }
                if (!report.businessDate().equals(c.businessDate())) {
                    throw new McmFormatException(file.fileName() + ": BusinessDate " + report.businessDate()
                            + " but the package is for " + c.businessDate());
                }
                if (report.primaryPeriod() == McmReport.STORE_PERIOD) {
                    stores.add(new Parsed(file, report));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the zip of " + c.businessDate(), e);
        }
        if (stores.size() != 1) {
            throw new McmFormatException("MCM package of " + c.businessDate() + " has " + stores.size()
                    + " store-level (PrimaryReportPeriod 2) files, expected 1");
        }
        return stores.get(0);
    }

    private record Parsed(Manifest.File file, McmReport report) {
    }
}
