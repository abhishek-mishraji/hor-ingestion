package com.handsoffretail.verifone;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** docs/test-data/expected_daily_tiki.csv: the golden counts per day (no personal data). */
public final class GoldenDays {

    /** The count columns the MVP golden test compares, in CSV order. */
    public static final List<String> COUNTS = List.of("tickets", "ticket_lines", "event_lines", "payment_lines",
            "card_payments", "age_checks", "cashier_events");

    private GoldenDays() {
    }

    public static Map<LocalDate, Row> load() {
        Path csv = Path.of("docs", "test-data", "expected_daily_tiki.csv");
        try {
            List<String> lines = Files.readAllLines(csv);
            List<String> header = Arrays.asList(lines.get(0).split(","));
            Map<LocalDate, Row> rows = new LinkedHashMap<>();
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cells = line.split(",");
                Map<String, Long> counts = new LinkedHashMap<>();
                for (String column : COUNTS) {
                    counts.put(column, Long.parseLong(cells[header.indexOf(column)]));
                }
                rows.put(LocalDate.parse(cells[header.indexOf("business_date")]), new Row(
                        Integer.parseInt(cells[header.indexOf("day_seq")]), counts,
                        "t".equals(cells[header.indexOf("counter_ties")])));
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record Row(int daySeq, Map<String, Long> counts, boolean counterTies) {
    }
}
