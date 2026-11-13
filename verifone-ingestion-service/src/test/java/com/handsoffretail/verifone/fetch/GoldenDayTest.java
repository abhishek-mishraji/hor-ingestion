package com.handsoffretail.verifone.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.handsoffretail.verifone.GoldenDays;
import com.handsoffretail.verifone.IngestionIntegrationTest;
import com.handsoffretail.verifone.TestStores;

/**
 * IMPLEMENTATION_PLAN.md Phase 4 golden test: every one of the 20 real days fetched through the real
 * single-day flow (local zip source); the stored counts equal docs/test-data/expected_daily_tiki.csv,
 * every hard check passes and the POS sales counter ties; each day loads in under 10 seconds.
 */
class GoldenDayTest extends IngestionIntegrationTest {

    private static final Duration MAX_PER_DAY = Duration.ofSeconds(10);
    private static final Map<String, String> CSV_TO_ROWS = Map.of("tickets", "tickets", "ticket_lines", "ticket_lines",
            "event_lines", "event_lines", "payment_lines", "payment_lines", "card_payments", "card_payments",
            "age_checks", "age_checks", "cashier_events", "cashier_events");

    @Test
    void everyRealDayLoadsWithTheGoldenCountsAndATiedCounter() {
        Map<LocalDate, GoldenDays.Row> golden = GoldenDays.load();
        List<String> table = new ArrayList<>();
        table.add(String.format("%-10s %4s %7s %6s %5s %5s %5s %4s %4s  %-6s %s", "day", "seq", "tickets", "lines", "evln",
                "pay", "card", "age", "evt", "ctr", "ms"));
        for (Map.Entry<LocalDate, GoldenDays.Row> day : golden.entrySet()) {
            FetchRequest done = fetch(TestStores.TIKI, day.getKey(), FetchRequest.Mode.REPLACE);
            assertThat(done.status()).as(day.getKey() + ": " + done.message().orElse("")).isEqualTo(FetchRequest.Status.LOADED);
            long ms = Duration.between(done.requestedAt(), done.finishedAt().orElseThrow()).toMillis();

            FetchQueryService.Detail detail = fetchQueries.detail(done.fetchRequestId());
            assertThat(detail.files()).hasSize(1);
            FetchQueries.LoadedFile file = detail.files().get(0);
            Map<String, Long> stored = new LinkedHashMap<>();
            CSV_TO_ROWS.forEach((csv, row) -> stored.put(csv, file.rows().get(row)));
            assertThat(stored).as(day.getKey() + " counts").containsExactlyInAnyOrderEntriesOf(day.getValue().counts());
            assertThat(file.daySeq()).isEqualTo(day.getValue().daySeq());
            assertThat(file.checks().stream().filter(FetchQueries.Check::hard).filter(c -> !c.passed()).toList())
                    .as(day.getKey() + " hard checks").isEmpty();
            FetchQueries.Check counter = file.checks().stream().filter(c -> c.name().equals("pos_sales_counter")).findFirst()
                    .orElseThrow();
            assertThat(counter.passed()).as(day.getKey() + " counter ties").isEqualTo(day.getValue().counterTies());
            assertThat(Duration.ofMillis(ms)).as(day.getKey() + " load time").isLessThan(MAX_PER_DAY);

            Map<String, Long> c = day.getValue().counts();
            table.add(String.format("%-10s %4d %7d %6d %5d %5d %5d %4d %4d  %-6s %d", day.getKey(), file.daySeq(),
                    c.get("tickets"), c.get("ticket_lines"), c.get("event_lines"), c.get("payment_lines"),
                    c.get("card_payments"), c.get("age_checks"), c.get("cashier_events"), counter.passed() ? "ties" : "OFF", ms));
        }
        table.forEach(System.out::println);
    }
}
