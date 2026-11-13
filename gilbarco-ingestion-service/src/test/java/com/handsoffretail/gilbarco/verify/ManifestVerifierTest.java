package com.handsoffretail.gilbarco.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.handsoffretail.gilbarco.TestBucket;
import com.handsoffretail.gilbarco.config.IngestionProperties;
import com.handsoffretail.gilbarco.source.ZipObject;

import tools.jackson.databind.json.JsonMapper;

/** Every rule of the day-package check, on small synthetic zips (plain unit test). */
class ManifestVerifierTest {

    private static final LocalDate DAY = LocalDate.parse("2026-09-24");
    private static final String CODE = TestBucket.STORE_CODE;
    private static final String DIR = "2026/september/2026-09-24/PJR/";
    private static final byte[] XML = "<NAXML-POSJournal/>".getBytes(StandardCharsets.ISO_8859_1);

    private final ManifestVerifier verifier = new ManifestVerifier(JsonMapper.builder().build(),
            new IngestionProperties.Fetch(Duration.ofMinutes(30), 1, 1, 1_000_000));

    @TempDir
    Path dir;

    @Test
    void aGoodZipIsVerifiedWithItsFilesInPathOrder() throws IOException {
        Path zip = TestBucket.empty().put(DAY, 1, "PJR", List.of(new TestBucket.Entry("PJR2.xml", XML),
                new TestBucket.Entry("PJR1.xml", XML)), TestBucket.Corruption.NONE);

        VerifiedZip verified = verifier.verify(key(zip, 1, "PJR"), zip, CODE);

        assertThat(verified.files()).extracting(Manifest.File::fileName).containsExactly("PJR1.xml", "PJR2.xml");
        assertThat(verified.manifest().fastMirrorVersion()).isEqualTo("3.1.0");
    }

    @Test
    void aWrongSha256IsRefused() throws IOException {
        Path zip = TestBucket.empty().put(DAY, 1, "PJR", List.of(new TestBucket.Entry("PJR1.xml", XML)),
                TestBucket.Corruption.WRONG_SHA256);

        assertRefused(zip, 1, "sha256");
    }

    @Test
    void theManifestMustMatchTheKeyTheTypeAndTheStore() throws IOException {
        Path zip = TestBucket.empty().put(DAY, 1, "PJR", List.of(new TestBucket.Entry("PJR1.xml", XML)),
                TestBucket.Corruption.NONE);

        assertThatThrownBy(() -> verifier.verify(key(zip, 2, "PJR"), zip, CODE)).isInstanceOf(ZipRejectedException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> verifier.verify(key(zip, 1, "PJR"), zip, "8730_IN-47872"))
                .isInstanceOf(ZipRejectedException.class).hasMessageContaining("store");
        assertThatThrownBy(() -> verifier.verify(key(zip, 1, "MCM"), zip, CODE)).isInstanceOf(ZipRejectedException.class)
                .hasMessageContaining("type");
    }

    @Test
    void pathsMustBeSafeUnderTheDayTypeFolderAndExactlyTheListedFiles() throws IOException {
        assertRefused(raw("../" + DIR + "PJR1.xml", 1, Map.of("../" + DIR + "PJR1.xml", XML)), 1, "unsafe path");
        assertRefused(raw(DIR + "PJR1.xml", 1, entries(DIR + "PJR1.xml", DIR + "extra.txt")), 1, "not in the manifest");
        assertRefused(raw(DIR + "PJR1.xml", 2, Map.of(DIR + "PJR1.xml", XML)), 1, "fileCount");
        assertRefused(raw("2026/september/2026-09-23/PJR/PJR1.xml", 1, Map.of("2026/september/2026-09-23/PJR/PJR1.xml", XML)),
                1, "not a file directly under");
        assertRefused(raw(DIR + "sub/PJR1.xml", 1, Map.of(DIR + "sub/PJR1.xml", XML)), 1, "not a file directly under");
    }

    @Test
    void aMissingManifestIsRefused() throws IOException {
        Path zip = dir.resolve("wolf_800_NY-13208_PJR_2026-09-24_v1.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry(DIR + "PJR1.xml"));
            z.write(XML);
        }

        assertRefused(zip, 1, "no manifest.json");
    }

    @Test
    void anUnknownManifestFieldIsRefused() throws IOException {
        Path zip = dir.resolve("wolf_800_NY-13208_PJR_2026-09-24_v1.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("manifest.json"));
            z.write("{\"store\":\"wolf_800_NY-13208\",\"surprise\":1}".getBytes(StandardCharsets.UTF_8));
        }

        assertRefused(zip, 1, "manifest.json is not valid");
    }

    private void assertRefused(Path zip, int version, String reason) {
        assertThatThrownBy(() -> verifier.verify(key(zip, version, "PJR"), zip, CODE)).isInstanceOf(ZipRejectedException.class)
                .hasMessageContaining(reason);
    }

    private static Map<String, byte[]> entries(String... paths) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (String p : paths) {
            m.put(p, XML);
        }
        return m;
    }

    /** A zip whose manifest lists one path (with the right sha256) and holds the given entries. */
    private Path raw(String listedPath, int fileCount, Map<String, byte[]> entries) throws IOException {
        Path zip = dir.resolve("wolf_800_NY-13208_PJR_2026-09-24_v1.zip");
        String manifest = "{\"store\":\"wolf_800_NY-13208\",\"type\":\"PJR\",\"businessDate\":\"2026-09-24\",\"version\":1,"
                + "\"createdAt\":\"2026-09-25T08:00:04.415061Z\",\"fastMirrorVersion\":\"3.1.0\",\"fileCount\":" + fileCount
                + ",\"files\":[{\"path\":\"" + listedPath + "\",\"size\":" + XML.length + ",\"modified\":\"2026-09-25T00:00:00Z\","
                + "\"sha256\":\"" + TestBucket.sha256(XML) + "\"}]}";
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
            }
            z.putNextEntry(new ZipEntry("manifest.json"));
            z.write(manifest.getBytes(StandardCharsets.UTF_8));
        }
        return zip;
    }

    private static ZipObject key(Path zip, int version, String type) {
        return new ZipObject("wolf_800_NY-13208/wolf_800_NY-13208/2026/september/2026-09-24/" + type + "/wolf_800_NY-13208_"
                + type + "_2026-09-24_v" + version + ".zip", "etag", 1, Instant.EPOCH, DAY, type, version);
    }
}
