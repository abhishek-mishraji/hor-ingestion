package com.handsoffretail.verifone.source;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The bucket layout on top of two storage primitives (list child folders, list objects under a
 * prefix). S3 and the local folder only implement the primitives and the download.
 */
public abstract class LayoutZipSource implements ZipSource {

    /** Child folder names directly under {@code prefix}, each ending with "/". */
    protected abstract List<String> listFolders(String prefix);

    /** Every object under {@code prefix} (any depth). */
    protected abstract List<StoredObject> listObjects(String prefix);

    @Override
    public Optional<ZipObject> findLatestCpjZip(String storeFolder, LocalDate businessDate) {
        List<ZipObject> candidates = new ArrayList<>();
        for (String month : monthFolders(storeFolder, YearMonth.from(businessDate))) {
            String dayPrefix = storeFolder + businessDate.getYear() + "/" + month + businessDate + "/";
            for (String type : listFolders(dayPrefix)) {
                if (!CpjKeys.isCpjFolder(type)) {
                    continue;
                }
                for (StoredObject object : listObjects(dayPrefix + type)) {
                    String name = object.key().substring(object.key().lastIndexOf('/') + 1);
                    CpjKeys.version(name, businessDate).ifPresent(version -> candidates.add(new ZipObject(object.key(),
                            object.etag(), object.sizeBytes(), object.lastModified(), businessDate, version)));
                }
            }
        }
        return CpjKeys.latest(candidates);
    }

    @Override
    public List<LocalDate> availableCpjDays(String storeFolder, YearMonth month) {
        TreeSet<LocalDate> days = new TreeSet<>();
        for (String folder : monthFolders(storeFolder, month)) {
            String monthPrefix = storeFolder + month.getYear() + "/" + folder;
            for (StoredObject object : listObjects(monthPrefix)) {
                CpjKeys.dayOf(object.key().substring(monthPrefix.length()))
                        .filter(day -> YearMonth.from(day).equals(month))
                        .ifPresent(days::add);
            }
        }
        return List.copyOf(days);
    }

    private List<String> monthFolders(String storeFolder, YearMonth month) {
        return listFolders(storeFolder + month.getYear() + "/").stream()
                .filter(folder -> CpjKeys.isMonthFolder(folder, month))
                .toList();
    }

    /** An object as storage lists it. */
    protected record StoredObject(String key, String etag, long sizeBytes, Instant lastModified) {
    }
}
