package com.handsoffretail.gilbarco.filetypes.pjr.parser;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A PJR file name: {@code PJR 340 yymmdd hhmmss counter .xml} (PJR3402609230004181533189.xml). The date in the
 * name is when Passport wrote the file, NOT the business date. The meaning of 340 is unknown, so other
 * {@code PJR<digits>.xml} names still load (without these parts).
 */
public record PjrFileName(String name, Optional<LocalDateTime> writtenAt, Optional<Long> counter) {

    private static final Pattern ANY = Pattern.compile("^PJR\\d+\\.xml$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTS =
            Pattern.compile("^PJR340(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d+)\\.xml$", Pattern.CASE_INSENSITIVE);
    private static final int CENTURY = 2000;

    /** @throws PjrFormatException when the name is not {@code PJR<digits>.xml} */
    public static PjrFileName of(String name) {
        if (!ANY.matcher(name).matches()) {
            throw new PjrFormatException(name + " is not a PJR file name (expected PJR<digits>.xml)");
        }
        Matcher m = PARTS.matcher(name);
        if (!m.matches()) {
            return new PjrFileName(name, Optional.empty(), Optional.empty());
        }
        try {
            LocalDateTime written = LocalDateTime.of(CENTURY + Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(6)));
            return new PjrFileName(name, Optional.of(written), Optional.of(Long.valueOf(m.group(7))));
        } catch (RuntimeException e) {
            return new PjrFileName(name, Optional.empty(), Optional.empty());
        }
    }
}
