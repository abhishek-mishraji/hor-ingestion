package com.handsoffretail.gilbarco.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The contract on a local folder laid out like the bucket, with the awkward cases built in: a capitalised month
 * folder, versions 2, 9 and 10, and zips of other file types next to the PJR ones.
 */
class LocalFolderZipSourceContractTest extends ZipSourceContract {

    private static final String FOLDER = "wolf_X1/wolf_X1/";
    private static final LocalDate DAY = LocalDate.parse("2026-09-24");

    @TempDir
    Path root;

    @BeforeEach
    void layout() throws IOException {
        Path day = root.resolve(FOLDER + "2026/September/2026-09-24/");
        write(day.resolve("PJR/wolf_X1_PJR_2026-09-24_v2.zip"), "two");
        write(day.resolve("PJR/wolf_X1_PJR_2026-09-24_v9.zip"), "nine");
        write(day.resolve("PJR/wolf_X1_PJR_2026-09-24_v10.zip"), "ten");
        write(day.resolve("MCM/wolf_X1_MCM_2026-09-24_v99.zip"), "other type");
        write(root.resolve(FOLDER + "2026/September/2026-09-25/FGM/wolf_X1_FGM_2026-09-25_v1.zip"), "no pjr that day");
    }

    @Override
    protected ZipSource source() {
        return new LocalFolderZipSource(root);
    }

    @Override
    protected String storeFolder() {
        return FOLDER;
    }

    @Override
    protected LocalDate dayWithZip() {
        return DAY;
    }

    @Override
    protected int expectedVersion() {
        return 10;
    }

    @Override
    protected LocalDate dayWithoutZip() {
        return LocalDate.parse("2026-09-25");
    }

    @Test
    void eachFileTypeIsFoundOnItsOwn() {
        assertThat(source().findLatestZip(FOLDER, DAY, "MCM")).hasValueSatisfying(z -> {
            assertThat(z.versionNo()).isEqualTo(99);
            assertThat(z.fileType()).isEqualTo("MCM");
        });
        assertThat(source().availableDays(FOLDER, YearMonth.of(2026, 9), "FGM")).containsExactly(LocalDate.parse("2026-09-25"));
        assertThat(source().findLatestZip(FOLDER, DAY, "TPM")).isEmpty();
    }

    @Test
    void twoZipsWithTheSameHighestVersionAreAnError() throws IOException {
        write(root.resolve(FOLDER + "2026/September/2026-09-24/PJR/Other_PJR_2026-09-24_v10.zip"), "duplicate");

        assertThatThrownBy(() -> source().findLatestZip(FOLDER, DAY, PJR)).isInstanceOf(ZipSourceException.class)
                .hasMessageContaining("version 10");
    }

    @Test
    void keysCannotEscapeTheRoot() {
        ZipObject outside = new ZipObject("../outside.zip", "x", 1, Instant.EPOCH, DAY, PJR, 1);

        assertThatThrownBy(() -> source().download(outside, root.resolve("x.zip"))).isInstanceOf(ZipSourceException.class);
        assertThat(source().bucket()).startsWith("local:");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
