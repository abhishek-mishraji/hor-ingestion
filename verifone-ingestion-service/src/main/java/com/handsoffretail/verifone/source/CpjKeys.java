package com.handsoffretail.verifone.source;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The bucket's naming rules for CPJ zips (pure functions, shared by every {@link ZipSource}). */
public final class CpjKeys {

    private static final Pattern DAY_ZIP = Pattern.compile("(?i)^(\\d{4}-\\d{2}-\\d{2})/CPJ/[^/]*_CPJ_(\\d{4}-\\d{2}-\\d{2})_v(\\d+)\\.zip$");
    private static final String TYPE_FOLDER = "CPJ/";

    private CpjKeys() {
    }

    /** "october" for October: the month folder name (matched ignoring case). */
    public static String monthName(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
    }

    public static boolean isMonthFolder(String folder, YearMonth month) {
        return folder.equalsIgnoreCase(monthName(month) + "/");
    }

    public static boolean isCpjFolder(String folder) {
        return folder.equalsIgnoreCase(TYPE_FOLDER);
    }

    /** The version of a zip named {@code <..>_CPJ_<date>_v<N>.zip} for that date, if it is one. */
    public static Optional<Integer> version(String fileName, LocalDate date) {
        Matcher m = Pattern.compile("(?i)^.*_CPJ_" + date + "_v(\\d+)\\.zip$").matcher(fileName);
        return m.matches() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    /**
     * The highest version among the day's candidate zips (v10 beats v9). Two different zips with the
     * same highest version are ambiguous.
     */
    public static Optional<ZipObject> latest(Collection<ZipObject> candidates) {
        Optional<ZipObject> best = candidates.stream().max(Comparator.comparingInt(ZipObject::versionNo));
        best.ifPresent(b -> {
            List<ZipObject> same = candidates.stream().filter(c -> c.versionNo() == b.versionNo()).toList();
            if (same.size() > 1) {
                throw new ZipSourceException("two CPJ zips with version " + b.versionNo() + ": "
                        + same.stream().map(ZipObject::key).toList());
            }
        });
        return best;
    }

    /** The business day of a key relative to its month folder, when it is a CPJ zip of that day. */
    public static Optional<LocalDate> dayOf(String keyInMonth) {
        Matcher m = DAY_ZIP.matcher(keyInMonth);
        if (!m.matches() || !m.group(1).equals(m.group(2))) {
            return Optional.empty();
        }
        return Optional.of(LocalDate.parse(m.group(1)));
    }
}
