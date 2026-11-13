package com.handsoffretail.verifone.source;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Lists and downloads a store's CPJ zips, laid out as
 * {@code <store folder><yyyy>/<month name>/<yyyy-mm-dd>/CPJ/<..>_CPJ_<yyyy-mm-dd>_v<N>.zip}.
 * Contract (checked by {@code ZipSourceContract} for every implementation):
 * <ul>
 *   <li>the month folder matches ignoring case; the highest {@code _vN} (compared as a number) wins;</li>
 *   <li>no zip for that day is {@code Optional.empty()}, never an exception;</li>
 *   <li>a real failure (no access, network, two zips with the same highest version) is a
 *       {@link ZipSourceException}.</li>
 * </ul>
 */
public interface ZipSource {

    /** Stored with every zip in ingest.s3_objects.bucket. */
    String bucket();

    Optional<ZipObject> findLatestCpjZip(String storeFolder, LocalDate businessDate);

    /** Days of the month that have at least one CPJ zip, ascending. */
    List<LocalDate> availableCpjDays(String storeFolder, YearMonth month);

    /** Writes the zip to {@code target} (which must not exist yet). */
    void download(ZipObject zip, Path target);
}
