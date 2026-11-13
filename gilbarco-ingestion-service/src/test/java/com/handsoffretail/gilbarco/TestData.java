package com.handsoffretail.gilbarco;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;

/**
 * The real Wolf PJR files of 22-30 Sep 2026 ({@code <HOR_TESTDATA_ROOT>/Wolf/XML_mmdd_mmdd/**}/PJR*.xml, 6,618
 * files), grouped by the BusinessDate inside each file, as FastMirror groups them. {@code HOR_TESTDATA_ROOT} comes
 * from the system property {@code hor.testdata.root}, the environment or {@code .env}. Tests that need the data
 * are skipped, with a message, when it is absent. Never copied into the repository.
 */
public final class TestData {

    private static final Pattern FOLDER = Pattern.compile("XML_\\d{4}_\\d{4}");
    private static final Pattern PJR_NAME = Pattern.compile("^PJR\\d+\\.xml$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUSINESS_DATE = Pattern.compile("<BusinessDate>(\\d{4}-\\d{2}-\\d{2})</BusinessDate>");
    private static TestData instance;

    private final Map<LocalDate, List<Path>> pjrByBusinessDate;
    private final int files;

    private TestData(Map<LocalDate, List<Path>> pjrByBusinessDate, int files) {
        this.pjrByBusinessDate = pjrByBusinessDate;
        this.files = files;
    }

    /** The test data, or the calling test is skipped. */
    public static TestData require() {
        Assumptions.assumeTrue(available(), "HOR_TESTDATA_ROOT/Wolf with XML_mmdd_mmdd folders not found: real-data tests skipped");
        return instance();
    }

    public static boolean available() {
        return wolf().filter(Files::isDirectory).isPresent();
    }

    static synchronized TestData instance() {
        if (instance == null) {
            instance = scan(wolf().orElseThrow());
        }
        return instance;
    }

    public Map<LocalDate, List<Path>> pjrByBusinessDate() {
        return pjrByBusinessDate;
    }

    /** Distinct PJR files found (by name). */
    public int files() {
        return files;
    }

    private static Optional<Path> wolf() {
        String property = System.getProperty("hor.testdata.root");
        Optional<String> value = property != null ? Optional.of(property) : TestEnv.get("HOR_TESTDATA_ROOT");
        return value.filter(v -> !v.isBlank()).map(v -> Path.of(v, "Wolf"));
    }

    private static TestData scan(Path wolf) {
        Map<String, Path> byName = new TreeMap<>();
        try (Stream<Path> dirs = Files.list(wolf)) {
            for (Path dir : dirs.filter(d -> FOLDER.matcher(d.getFileName().toString()).matches()).sorted().toList()) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(p -> PJR_NAME.matcher(p.getFileName().toString()).matches())
                            .forEach(p -> byName.putIfAbsent(p.getFileName().toString(), p));
                }
            }
            Map<LocalDate, List<Path>> byDay = new TreeMap<>();
            for (Path file : byName.values()) {
                Matcher m = BUSINESS_DATE.matcher(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
                if (!m.find()) {
                    throw new IllegalStateException("no BusinessDate in " + file);
                }
                byDay.computeIfAbsent(LocalDate.parse(m.group(1)), d -> new ArrayList<>()).add(file);
            }
            return new TestData(byDay, byName.size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
