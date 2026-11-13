package com.handsoffretail.verifone;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.handsoffretail.verifone.source.CpjKeys;

/**
 * A folder laid out exactly like the S3 bucket, built from the real CPJ files in a temporary folder
 * (IMPLEMENTATION_PLAN.md Appendix A). Never inside the repository.
 */
public final class TestBucket {

    public static final String STORE_CODE = "8730_IN-47872";
    public static final String STORE_FOLDER = "Tiki_8730_IN-47872/Tiki_8730_IN-47872/";
    private static final String STORE_PREFIX = "Tiki_8730_IN-47872";
    private static TestBucket shared;

    private final Path root;

    private TestBucket(Path root) {
        this.root = root;
    }

    /** One bucket per test JVM with every real day as v1 (the real 2026-10-11 zip as it is). */
    public static synchronized TestBucket shared() throws IOException {
        if (shared == null) {
            TestData data = TestData.require();
            TestBucket bucket = new TestBucket(Files.createTempDirectory("hor-test-bucket"));
            for (LocalDate day : data.days()) {
                Path realZip = data.zips().get(day);
                if (realZip != null) {
                    Path target = bucket.zipPath(day, 1);
                    Files.createDirectories(target.getParent());
                    Files.copy(realZip, target);
                } else {
                    String name = data.xmlFiles().get(day).getFileName().toString();
                    try (InputStream in = Files.newInputStream(data.xmlFiles().get(day))) {
                        bucket.put(day, 1, List.of(new Entry(name, in.readAllBytes())), Corruption.NONE);
                    }
                }
            }
            shared = bucket;
        }
        return shared;
    }

    /** An empty bucket of its own (for tests that add special zips). */
    public static TestBucket empty() throws IOException {
        return new TestBucket(Files.createTempDirectory("hor-test-bucket"));
    }

    public Path root() {
        return root;
    }

    /** A CPJ file to pack: its name (CPJ<date>-<seq>.xml) and content. */
    public record Entry(String fileName, byte[] content) {
    }

    /** Deliberate damage for failure tests. */
    public enum Corruption {
        NONE,
        WRONG_SHA256
    }

    /** Writes {@code <folder><yyyy>/<month>/<date>/CPJ/Tiki_..._CPJ_<date>_v<N>.zip} with a manifest. */
    public Path put(LocalDate day, int version, List<Entry> entries, Corruption corruption) throws IOException {
        Path zip = zipPath(day, version);
        Files.createDirectories(zip.getParent());
        String folder = day.getYear() + "/" + CpjKeys.monthName(YearMonth.from(day)) + "/" + day + "/CPJ/";
        List<String> files = new ArrayList<>();
        for (Entry e : entries) {
            String sha = sha256(e.content());
            if (corruption == Corruption.WRONG_SHA256) {
                sha = sha.substring(1) + (sha.charAt(0) == '0' ? '1' : '0');
            }
            files.add("{ \"path\": \"" + folder + e.fileName() + "\", \"size\": " + e.content().length
                    + ", \"modified\": \"2026-10-12T02:07:30Z\", \"sha256\": \"" + sha + "\" }");
        }
        String manifest = "{ \"store\": \"" + STORE_PREFIX + "\", \"type\": \"CPJ\", \"businessDate\": \"" + day
                + "\", \"version\": " + version + ", \"createdAt\": \"" + Instant.parse("2026-10-16T13:56:03Z")
                + "\", \"fastMirrorVersion\": \"3.0.0\", \"fileCount\": " + entries.size() + ", \"files\": [ "
                + String.join(", ", files) + " ] }";
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("manifest.json"));
            z.write(manifest.getBytes(StandardCharsets.UTF_8));
            for (Entry e : entries) {
                z.putNextEntry(new ZipEntry(folder + e.fileName()));
                z.write(e.content());
            }
        }
        return zip;
    }

    public Path zipPath(LocalDate day, int version) {
        return root.resolve(STORE_FOLDER + day.getYear() + "/" + CpjKeys.monthName(YearMonth.from(day)) + "/" + day + "/CPJ/"
                + STORE_PREFIX + "_CPJ_" + day + "_v" + version + ".zip");
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads a real day's CPJ XML. */
    public static byte[] realXml(LocalDate day) {
        try (InputStream in = TestData.require().openCpj(day)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
