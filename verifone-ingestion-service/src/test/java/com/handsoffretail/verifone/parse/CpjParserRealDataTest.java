package com.handsoffretail.verifone.parse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.handsoffretail.verifone.GoldenDays;
import com.handsoffretail.verifone.TestData;
import com.handsoffretail.verifone.parse.handlers.CpjParser;
import com.handsoffretail.verifone.parse.model.CashierEvent;
import com.handsoffretail.verifone.parse.model.CpjFileHeader;
import com.handsoffretail.verifone.parse.model.CpjFileTrailer;
import com.handsoffretail.verifone.parse.model.CpjSink;
import com.handsoffretail.verifone.parse.model.ParseFacts;
import com.handsoffretail.verifone.parse.model.Payment;
import com.handsoffretail.verifone.parse.model.Ticket;

/** The parser alone (no database) on all 20 real days: every element mapped, counts equal the golden CSV. */
class CpjParserRealDataTest {

    @Test
    void everyRealDayParsesWithNothingUnmappedAndTheGoldenCounts() throws Exception {
        TestData data = TestData.require();
        Map<LocalDate, GoldenDays.Row> golden = GoldenDays.load();
        assertThat(data.days()).containsAll(golden.keySet());
        List<String> report = new ArrayList<>();
        for (LocalDate day : golden.keySet()) {
            CountingSink sink = new CountingSink();
            ParseFacts facts;
            try (InputStream in = data.openCpj(day)) {
                facts = CpjParser.standard().parse(in, sink);
            }
            report.add(day + " " + sink.counts);
            assertThat(sink.counts).as(day.toString()).isEqualTo(golden.get(day).counts());
            assertThat(sink.daySeq).as(day + " day seq").isEqualTo(golden.get(day).daySeq());
            assertThat(facts.storeNumberMismatches()).as(day + " storeNumber").isEmpty();
            assertThat(facts.rootCopiesEqual()).as(day + " root copies").isEqualTo(facts.rootCopies()).isPositive();
            assertThat(facts.registerConflicts()).as(day + " registers").isEmpty();
            assertThat(sink.ended).isTrue();
        }
        report.forEach(System.out::println);
    }

    /** Counts what a writer would store, in the CSV's terms. */
    private static final class CountingSink implements CpjSink {
        final Map<String, Long> counts = new LinkedHashMap<>();
        int daySeq;
        boolean ended;

        CountingSink() {
            GoldenDays.COUNTS.forEach(c -> counts.put(c, 0L));
        }

        @Override
        public void begin(CpjFileHeader header) {
            daySeq = header.daySeq();
        }

        @Override
        public void ticket(Ticket t) {
            add("tickets", 1);
            add("ticket_lines", t.lines().size());
            add("payment_lines", t.payments().size());
            add("card_payments", t.payments().stream().filter(p -> p.card().isPresent()).count());
            add("age_checks", t.value().flatMap(Ticket.Value::ageCheck).isPresent() ? 1 : 0);
        }

        @Override
        public void cashierEvent(CashierEvent e) {
            add("cashier_events", 1);
            add("event_lines", e.lines().size());
        }

        @Override
        public void end(CpjFileTrailer trailer) {
            ended = true;
        }

        private void add(String column, long n) {
            counts.merge(column, n, Long::sum);
        }
    }
}
