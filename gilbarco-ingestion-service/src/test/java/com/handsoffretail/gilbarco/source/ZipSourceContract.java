package com.handsoffretail.gilbarco.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract of {@link ZipSource}: every implementation must pass these (IMPLEMENTATION_PLAN.md §4, Liskov). */
abstract class ZipSourceContract {

    protected static final String PJR = "PJR";

    @TempDir
    Path downloads;

    protected abstract ZipSource source();

    protected abstract String storeFolder();

    /** A day with zips of {@link #PJR}, and the version the source must choose. */
    protected abstract LocalDate dayWithZip();

    protected abstract int expectedVersion();

    protected abstract LocalDate dayWithoutZip();

    @Test
    void theLatestZipOfADayIsFoundWithItsVersion() {
        Optional<ZipObject> zip = source().findLatestZip(storeFolder(), dayWithZip(), PJR);

        assertThat(zip).hasValueSatisfying(z -> {
            assertThat(z.versionNo()).isEqualTo(expectedVersion());
            assertThat(z.businessDate()).isEqualTo(dayWithZip());
            assertThat(z.key()).startsWith(storeFolder()).endsWith("_" + PJR + "_" + dayWithZip() + "_v" + expectedVersion() + ".zip");
            assertThat(z.etag()).isNotBlank().doesNotContain("\"");
            assertThat(z.sizeBytes()).isPositive();
        });
    }

    @Test
    void aDayWithoutAZipIsEmptyNotAnError() {
        assertThat(source().findLatestZip(storeFolder(), dayWithoutZip(), PJR)).isEmpty();
        assertThat(source().findLatestZip("No_Such_Store/", dayWithZip(), PJR)).isEmpty();
    }

    @Test
    void availableDaysListTheMonthsDaysWithAZip() {
        List<LocalDate> days = source().availableDays(storeFolder(), YearMonth.from(dayWithZip()), PJR);

        assertThat(days).contains(dayWithZip()).doesNotContain(dayWithoutZip()).isSorted()
                .allSatisfy(d -> assertThat(YearMonth.from(d)).isEqualTo(YearMonth.from(dayWithZip())));
        assertThat(source().availableDays("No_Such_Store/", YearMonth.from(dayWithZip()), PJR)).isEmpty();
    }

    @Test
    void downloadWritesTheZipWhoseMd5IsItsEtag() throws Exception {
        ZipObject zip = source().findLatestZip(storeFolder(), dayWithZip(), PJR).orElseThrow();
        Path target = downloads.resolve("zip.zip");

        source().download(zip, target);

        assertThat(Files.size(target)).isEqualTo(zip.sizeBytes());
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(target), MessageDigest.getInstance("MD5"))) {
            in.transferTo(OutputStream.nullOutputStream());
            assertThat(HexFormat.of().formatHex(in.getMessageDigest().digest())).isEqualTo(zip.etag());
        }
    }
}
