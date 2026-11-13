package com.handsoffretail.gilbarco.source;

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

/**
 * The bucket's naming rules for FastMirror day packages of any file type (pure functions, shared by every
 * {@link ZipSource}): {@code <store folder><yyyy>/<month>/<yyyy-MM-dd>/<TYPE>/<store>_<TYPE>_<yyyy-MM-dd>_v<n>.zip}.
 */
public final class GilbarcoKeys {

    private static final Pattern FILE_TYPE = Pattern.compile("^[A-Z0-9]{2,16}$");

    private GilbarcoKeys() {
    }

    /** "september" for September: the month folder name (matched ignoring case). */
    public static String monthName(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
    }

    public static boolean isMonthFolder(String folder, YearMonth month) {
        return folder.equalsIgnoreCase(monthName(month) + "/");
    }

    public static boolean isTypeFolder(String folder, String fileType) {
        return folder.equalsIgnoreCase(requireType(fileType) + "/");
    }

    /** The folder of a day's files of one type inside the zip and below the store folder: 2026/september/2026-09-24/PJR/. */
    public static String dayTypeFolder(LocalDate day, String fileType) {
        return day.getYear() + "/" + monthName(YearMonth.from(day)) + "/" + day + "/" + requireType(fileType) + "/";
    }

    /** The version of a zip named {@code <..>_<TYPE>_<date>_v<N>.zip} for that type and date, if it is one. */
    public static Optional<Integer> version(String fileName, LocalDate date, String fileType) {
        Matcher m = Pattern.compile("(?i)^.*_" + requireType(fileType) + "_" + date + "_v([1-9]\\d*)\\.zip$").matcher(fileName);
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
                throw new ZipSourceException("two " + b.fileType() + " zips with version " + b.versionNo() + ": "
                        + same.stream().map(ZipObject::key).toList());
            }
        });
        return best;
    }

    /** The business day of a key relative to its month folder, when it is a zip of that type and day. */
    public static Optional<LocalDate> dayOf(String keyInMonth, String fileType) {
        String type = requireType(fileType);
        Matcher m = Pattern.compile("(?i)^(\\d{4}-\\d{2}-\\d{2})/" + type + "/[^/]*_" + type
                + "_(\\d{4}-\\d{2}-\\d{2})_v[1-9]\\d*\\.zip$").matcher(keyInMonth);
        if (!m.matches() || !m.group(1).equals(m.group(2))) {
            return Optional.empty();
        }
        return Optional.of(LocalDate.parse(m.group(1)));
    }

    /** A file type is its 2-16 letter / digit code in capitals (PJR, MCM, ...); anything else could break a pattern. */
    static String requireType(String fileType) {
        if (fileType == null || !FILE_TYPE.matcher(fileType).matches()) {
            throw new IllegalArgumentException("not a file type: " + fileType);
        }
        return fileType;
    }
}
