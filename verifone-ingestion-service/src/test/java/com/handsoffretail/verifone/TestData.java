package com.handsoffretail.verifone;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Assumptions;

/**
 * The real CPJ test files (IMPLEMENTATION_PLAN.md Appendix A). Found under {@code hor.testdata.root}
 * (system property {@code hor.testdata.root}, or {@code HOR_TESTDATA_ROOT} in the environment or {@code .env}).
 * Tests that need them are skipped, with a message, when the folder is absent. Never copied into the repo.
 */
public final class TestData {

    public static final Pattern CPJ_NAME = Pattern.compile("CPJ(\\d{4}-\\d{2}-\\d{2})-(\\d+)\\.xml");
    private static final Pattern ZIP_NAME = Pattern.compile(".*_CPJ_(\\d{4}-\\d{2}-\\d{2})_v(\\d+)\\.zip");
    private static final List<String> APPENDIX_A_FOLDERS = List.of("/tiki_recall_days/", "/data/processed/");
    private static final Set<String> PRUNE = Set.of("node_modules", ".git", "build", "dist", "graphify-out", ".gradle");
    private static TestData instance;

    private final Map<LocalDate, Path> xmlFiles;
    private final Map<LocalDate, Path> zips;

    private TestData(Map<LocalDate, Path> xmlFiles, Map<LocalDate, Path> zips) {
        this.xmlFiles = xmlFiles;
        this.zips = zips;
    }

    /** The test data, or the calling test is skipped. */
    public static synchronized TestData require() {
        if (instance == null) {
            Optional<Path> root = root();
            Assumptions.assumeTrue(root.isPresent() && Files.isDirectory(root.get()),
                    "hor.testdata.root is not set or missing: real-data tests skipped");
            instance = scan(root.get());
        }
        return instance;
    }

    public Map<LocalDate, Path> xmlFiles() {
        return xmlFiles;
    }

    public Map<LocalDate, Path> zips() {
        return zips;
    }

    /** Every day: a plain CPJ file, or the CPJ inside a real zip. */
    public List<LocalDate> days() {
        TreeMap<LocalDate, Boolean> all = new TreeMap<>();
        xmlFiles.keySet().forEach(d -> all.put(d, true));
        zips.keySet().forEach(d -> all.put(d, true));
        return new ArrayList<>(all.keySet());
    }

    /** Opens the day's CPJ XML (from a plain file or from inside its zip). Caller closes the stream. */
    public InputStream openCpj(LocalDate day) throws IOException {
        Path xml = xmlFiles.get(day);
        if (xml != null) {
            return Files.newInputStream(xml);
        }
        ZipFile zip = new ZipFile(zips.get(day).toFile());
        ZipEntry entry = zip.stream().filter(e -> CPJ_NAME.matcher(Path.of(e.getName()).getFileName().toString()).matches())
                .findFirst().orElseThrow();
        InputStream in = zip.getInputStream(entry);
        return new java.io.FilterInputStream(in) {
            @Override
            public void close() throws IOException {
                super.close();
                zip.close();
            }
        };
    }

    private static Optional<Path> root() {
        String property = System.getProperty("hor.testdata.root");
        Optional<String> value = property != null ? Optional.of(property) : TestEnv.get("HOR_TESTDATA_ROOT");
        return value.filter(v -> !v.isBlank()).map(Path::of);
    }

    private static TestData scan(Path root) {
        Map<LocalDate, Path> xml = new TreeMap<>();
        Map<LocalDate, Path> zips = new TreeMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return PRUNE.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    Matcher cpj = CPJ_NAME.matcher(name);
                    Matcher zip = ZIP_NAME.matcher(name);
                    if (cpj.matches()) {
                        add(xml, LocalDate.parse(cpj.group(1)), file);
                    } else if (zip.matches()) {
                        add(zips, LocalDate.parse(zip.group(1)), file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new TestData(xml, zips);
    }

    /**
     * The same day found twice: identical sizes are the same file; otherwise the copy in an Appendix A
     * folder wins (the golden CSV was made from those); any other conflict fails.
     */
    private static void add(Map<LocalDate, Path> files, LocalDate day, Path file) {
        Path known = files.putIfAbsent(day, file);
        try {
            if (known == null || Files.size(known) == Files.size(file)) {
                return;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        boolean knownListed = inAppendixA(known);
        boolean fileListed = inAppendixA(file);
        if (knownListed == fileListed) {
            throw new IllegalStateException("two different test files for " + day + ": " + known + " and " + file);
        }
        if (fileListed) {
            files.put(day, file);
        }
    }

    private static boolean inAppendixA(Path file) {
        String path = file.toString().replace('\\', '/');
        return APPENDIX_A_FOLDERS.stream().anyMatch(path::contains);
    }
}
