package com.handsoffretail.verifone.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.handsoffretail.verifone.TestBucket;
import com.handsoffretail.verifone.config.IngestionProperties;
import com.handsoffretail.verifone.source.ZipObject;

import tools.jackson.databind.json.JsonMapper;

/** Every rule of the zip check, on small synthetic zips (plain unit test). */
class ManifestVerifierTest {

    private static final LocalDate DAY = LocalDate.parse("2026-03-07");
    private static final String CODE = "8730_IN-47872";
    private static final String DIR = "2026/march/2026-03-07/CPJ/";
    private static final byte[] XML = "<transSet/>".getBytes(StandardCharsets.UTF_8);

    private final ManifestVerifier verifier = new ManifestVerifier(JsonMapper.builder().build(),
            new IngestionProperties.Fetch(java.time.Duration.ofMinutes(30), 1, 1, 1_000_000));

    @TempDir
    Path dir;

    @Test
    void aGoodZipIsVerifiedWithItsCpjFilesInDayOrder() throws IOException {
        TestBucket bucket = TestBucket.empty();
        Path zip = bucket.put(DAY, 1, List.of(new TestBucket.Entry("CPJ2026-03-07-12.xml", XML),
                new TestBucket.Entry("CPJ2026-03-07-11.xml", XML)), TestBucket.Corruption.NONE);

        VerifiedZip verified = verifier.verify(key(zip, 1), zip, CODE);

        assertThat(verified.cpjFiles()).extracting(VerifiedZip.CpjEntry::daySeq).containsExactly(11, 12);
    }

    @Test
    void aWrongSha256IsRefused() throws IOException {
        Path zip = TestBucket.empty().put(DAY, 1, List.of(new TestBucket.Entry("CPJ2026-03-07-1.xml", XML)),
                TestBucket.Corruption.WRONG_SHA256);

        assertRefused(zip, 1, "sha256");
    }

    @Test
    void theManifestMustMatchTheKeyAndTheStore() throws IOException {
        Path zip = TestBucket.empty().put(DAY, 1, List.of(new TestBucket.Entry("CPJ2026-03-07-1.xml", XML)),
                TestBucket.Corruption.NONE);

        assertThatThrownBy(() -> verifier.verify(key(zip, 2), zip, CODE)).isInstanceOf(ZipRejectedException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> verifier.verify(key(zip, 1), zip, "800_NY-13208")).isInstanceOf(ZipRejectedException.class)
                .hasMessageContaining("store");
    }

    @Test
    void pathsMustBeSafeAndExactlyTheListedFilesMustBeInTheZip() throws IOException {
        assertRefused(raw("../" + DIR + "CPJ2026-03-07-1.xml", 1, Map.of("../" + DIR + "CPJ2026-03-07-1.xml", XML)), 1, "unsafe path");
        assertRefused(raw(DIR + "CPJ2026-03-07-1.xml", 1, Map.of(DIR + "CPJ2026-03-07-1.xml", XML,
                DIR + "extra.txt", XML)), 1, "not in the manifest");
        assertRefused(raw(DIR + "CPJ2026-03-07-1.xml", 2, Map.of(DIR + "CPJ2026-03-07-1.xml", XML)), 1, "fileCount");
        assertRefused(raw(DIR + "ISM2026-03-07-1.xml", 1, Map.of(DIR + "ISM2026-03-07-1.xml", XML)), 1, "not a CPJ file");
    }

    @Test
    void aMissingManifestIsRefused() throws IOException {
        Path zip = dir.resolve("Tiki_8730_IN-47872_CPJ_2026-03-07_v1.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry(DIR + "CPJ2026-03-07-1.xml"));
            z.write(XML);
        }

        assertRefused(zip, 1, "no manifest.json");
    }

    private void assertRefused(Path zip, int version, String reason) {
        assertThatThrownBy(() -> verifier.verify(key(zip, version), zip, CODE)).isInstanceOf(ZipRejectedException.class)
                .hasMessageContaining(reason);
    }

    /** A zip whose manifest lists one path (with the right sha256) and holds the given entries. */
    private Path raw(String listedPath, int fileCount, Map<String, byte[]> entries) throws IOException {
        Path zip = dir.resolve("Tiki_8730_IN-47872_CPJ_2026-03-07_v1.zip");
        String sha = java.util.HexFormat.of().formatHex(sha256(XML));
        String manifest = "{\"store\":\"Tiki_8730_IN-47872\",\"type\":\"CPJ\",\"businessDate\":\"2026-03-07\",\"version\":1,"
                + "\"createdAt\":\"2026-03-08T00:00:00Z\",\"fastMirrorVersion\":\"3.0.0\",\"fileCount\":" + fileCount
                + ",\"files\":[{\"path\":\"" + listedPath + "\",\"size\":" + XML.length + ",\"modified\":\"2026-03-08T00:00:00Z\","
                + "\"sha256\":\"" + sha + "\"}]}";
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("manifest.json"));
            z.write(manifest.getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
            }
        }
        return zip;
    }

    private static byte[] sha256(byte[] content) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(content);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ZipObject key(Path zip, int version) {
        return new ZipObject("Tiki_8730_IN-47872/Tiki_8730_IN-47872/2026/march/2026-03-07/CPJ/"
                + zip.getFileName().toString().replaceAll("_v\\d+\\.zip$", "_v" + version + ".zip"), "etag", 1, Instant.EPOCH,
                DAY, version);
    }
}
