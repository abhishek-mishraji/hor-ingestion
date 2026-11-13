package com.handsoffretail.verifone.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The contract on a local folder laid out like the bucket, with the awkward cases built in: a
 * capitalised month folder, versions 2, 9 and 10, and zips of other file types next to the CPJ ones.
 */
class LocalFolderZipSourceContractTest extends ZipSourceContract {

    private static final String FOLDER = "Store_X1/Store_X1/";
    private static final LocalDate DAY = LocalDate.parse("2026-10-11");

    @TempDir
    Path root;

    @BeforeEach
    void layout() throws IOException {
        Path day = root.resolve(FOLDER + "2026/October/2026-10-11/");
        write(day.resolve("CPJ/Store_X1_CPJ_2026-10-11_v2.zip"), "two");
        write(day.resolve("CPJ/Store_X1_CPJ_2026-10-11_v9.zip"), "nine");
        write(day.resolve("CPJ/Store_X1_CPJ_2026-10-11_v10.zip"), "ten");
        write(day.resolve("PJR/Store_X1_PJR_2026-10-11_v99.zip"), "other type");
        write(root.resolve(FOLDER + "2026/October/2026-10-12/ISM/Store_X1_ISM_2026-10-12_v1.zip"), "no cpj that day");
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
        return LocalDate.parse("2026-10-12");
    }

    @Test
    void twoZipsWithTheSameHighestVersionAreAnError() throws IOException {
        write(root.resolve(FOLDER + "2026/October/2026-10-11/CPJ/Other_CPJ_2026-10-11_v10.zip"), "duplicate");

        assertThatThrownBy(() -> source().findLatestCpjZip(FOLDER, DAY)).isInstanceOf(ZipSourceException.class)
                .hasMessageContaining("version 10");
    }

    @Test
    void keysCannotEscapeTheRoot() {
        ZipObject outside = new ZipObject("../outside.zip", "x", 1, java.time.Instant.EPOCH, DAY, 1);

        assertThatThrownBy(() -> source().download(outside, root.resolve("x.zip"))).isInstanceOf(ZipSourceException.class);
        assertThat(source().bucket()).startsWith("local:");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
