package com.handsoffretail.gilbarco;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.handsoffretail.gilbarco.source.GilbarcoKeys;

/**
 * A temporary folder laid out exactly like the S3 bucket, with zips built the way FastMirror 3.1.0 builds them
 * (DAY_PACKAGES_Zip.md section 3: entries {@code <yyyy>/<month>/<date>/<TYPE>/<file>} sorted by path, then
 * manifest.json last). The shared bucket holds every real Wolf PJR day of the test data as v1; tests add
 * synthetic days of their own. Never inside the repository.
 */
public final class TestBucket {

    public static final String STORE_CODE = "800_NY-13208";
    public static final String STORE_PREFIX = "wolf_800_NY-13208";
    public static final String STORE_FOLDER = STORE_PREFIX + "/" + STORE_PREFIX + "/";
    public static final String PJR = "PJR";
    private static TestBucket shared;

    private final Path root;

    private TestBucket(Path root) {
        this.root = root;
    }

    /** One bucket per test JVM: every real Wolf PJR business day as v1 (empty when the test data is absent). */
    public static synchronized TestBucket shared() throws IOException {
        if (shared == null) {
            TestBucket bucket = new TestBucket(Files.createTempDirectory("hor-gilbarco-bucket"));
            if (TestData.available()) {
                for (Map.Entry<LocalDate, List<Path>> day : TestData.instance().pjrByBusinessDate().entrySet()) {
                    List<Entry> entries = new ArrayList<>();
                    for (Path file : day.getValue()) {
                        entries.add(new Entry(file.getFileName().toString(), Files.readAllBytes(file)));
                    }
                    bucket.put(day.getKey(), 1, PJR, entries, Corruption.NONE);
                }
            }
            shared = bucket;
        }
        return shared;
    }

    /** An empty bucket of its own (for tests that build special zips). */
    public static TestBucket empty() throws IOException {
        return new TestBucket(Files.createTempDirectory("hor-gilbarco-bucket"));
    }

    public Path root() {
        return root;
    }

    /** A POS file to pack: its original name and content. */
    public record Entry(String fileName, byte[] content) {

        public static Entry of(String fileName, String xml) {
            return new Entry(fileName, xml.getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    /** Deliberate damage for failure tests. */
    public enum Corruption {
        NONE,
        WRONG_SHA256
    }

    /** Writes {@code <folder><yyyy>/<month>/<date>/<TYPE>/wolf_800_NY-13208_<TYPE>_<date>_v<N>.zip} with a manifest. */
    public Path put(LocalDate day, int version, String type, List<Entry> entries, Corruption corruption) throws IOException {
        Path zip = zipPath(day, version, type);
        Files.createDirectories(zip.getParent());
        Files.deleteIfExists(zip);
        String folder = GilbarcoKeys.dayTypeFolder(day, type);
        List<Entry> sorted = entries.stream().sorted(Comparator.comparing(Entry::fileName)).toList();
        List<String> files = new ArrayList<>();
        for (Entry e : sorted) {
            String sha = sha256(e.content());
            if (corruption == Corruption.WRONG_SHA256) {
                sha = sha.substring(1) + (sha.charAt(0) == '0' ? '1' : '0');
            }
            files.add("{ \"path\": \"" + folder + e.fileName() + "\", \"size\": " + e.content().length
                    + ", \"modified\": \"2026-09-25T04:04:18.123456Z\", \"sha256\": \"" + sha + "\" }");
        }
        String manifest = "{\n  \"store\": \"" + STORE_PREFIX + "\",\n  \"type\": \"" + type + "\",\n  \"businessDate\": \""
                + day + "\",\n  \"version\": " + version + ",\n  \"createdAt\": \"2026-09-25T08:00:04Z\",\n"
                + "  \"fastMirrorVersion\": \"3.1.0\",\n  \"fileCount\": " + entries.size() + ",\n  \"files\": [\n    "
                + String.join(",\n    ", files) + "\n  ]\n}\n";
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (Entry e : sorted) {
                z.putNextEntry(new ZipEntry(folder + e.fileName()));
                z.write(e.content());
            }
            z.putNextEntry(new ZipEntry("manifest.json"));
            z.write(manifest.getBytes(StandardCharsets.UTF_8));
        }
        return zip;
    }

    public Path zipPath(LocalDate day, int version, String type) {
        return root.resolve(STORE_FOLDER + GilbarcoKeys.dayTypeFolder(day, type) + STORE_PREFIX + "_" + type + "_" + day
                + "_v" + version + ".zip");
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
