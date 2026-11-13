package com.handsoffretail.verifone.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.handsoffretail.verifone.IngestionIntegrationTest;
import com.handsoffretail.verifone.TestBucket;
import com.handsoffretail.verifone.TestStores;
import com.handsoffretail.auth.client.DomainException;

/** The fetch rules of IMPLEMENTATION_PLAN.md Phase 4 (REPLACE, REFUSE, concurrency, failures, NOT_FOUND, closes). */
class FetchBehaviourTest extends IngestionIntegrationTest {

    private static final LocalDate SEP_01 = LocalDate.parse("2026-09-01");
    private static final LocalDate SEP_02 = LocalDate.parse("2026-09-02");
    private static final LocalDate SEP_03 = LocalDate.parse("2026-09-03");
    private static final int SEQ_01 = 578;
    private static final int SEQ_02 = 579;
    private static final int SEQ_03 = 580;

    @Test
    void replaceTwiceGivesIdenticalCountsAndNoDuplicates() {
        FetchRequest first = fetch(TestStores.TIKI, SEP_01, FetchRequest.Mode.REPLACE);
        Map<String, Long> firstRows = rows(first);
        FetchRequest second = fetch(TestStores.TIKI, SEP_01, FetchRequest.Mode.REPLACE);

        assertThat(first.status()).isEqualTo(FetchRequest.Status.LOADED);
        assertThat(second.status()).isEqualTo(FetchRequest.Status.LOADED);
        assertThat(rows(second)).isEqualTo(firstRows);
        assertThat(fetchQueries.detail(first.fetchRequestId()).files()).as("REPLACE removed the first load").isEmpty();
        assertThat(count("SELECT count(*) FROM cpj.cpj_files WHERE store_id = ? AND business_date = ?", TestStores.TIKI, SEP_01))
                .isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM cpj.tickets t JOIN cpj.cpj_files f USING (cpj_file_id)
                WHERE f.store_id = ? AND f.business_date = ?""", TestStores.TIKI, SEP_01)).isEqualTo(537);
    }

    @Test
    void refuseOnALoadedDayIsRefusedAndChangesNothing() {
        fetch(TestStores.TIKI, SEP_02, FetchRequest.Mode.REPLACE);
        long fileBefore = count("SELECT cpj_file_id FROM cpj.cpj_files WHERE store_id = ? AND business_date = ?",
                TestStores.TIKI, SEP_02);

        FetchRequest refused = fetch(TestStores.TIKI, SEP_02, FetchRequest.Mode.REFUSE);

        assertThat(refused.status()).isEqualTo(FetchRequest.Status.REFUSED);
        assertThat(refused.message()).hasValueSatisfying(m -> assertThat(m).contains("already loaded"));
        assertThat(count("SELECT cpj_file_id FROM cpj.cpj_files WHERE store_id = ? AND business_date = ?",
                TestStores.TIKI, SEP_02)).isEqualTo(fileBefore);
    }

    @Test
    void twoConcurrentFetchesOfOneDayOneIsRefusedByTheRunningGuard() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Long>> starts = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            starts.add(pool.submit(() -> {
                go.await();
                return fetches.start(TestStores.TIKI, SEP_03, FetchRequest.Mode.REPLACE, TestStores.ADMIN_ID);
            }));
        }
        go.countDown();
        List<Long> started = new ArrayList<>();
        List<Throwable> refused = new ArrayList<>();
        for (Future<Long> start : starts) {
            try {
                started.add(start.get());
            } catch (java.util.concurrent.ExecutionException e) {
                refused.add(e.getCause());
            }
        }
        pool.shutdown();

        assertThat(started).hasSize(1);
        assertThat(refused).singleElement().isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo("FETCH_RUNNING"));
        assertThat(awaitEnd(started.get(0)).status()).isEqualTo(FetchRequest.Status.LOADED);
    }

    @Test
    void aCorruptedSha256FailsAndTheOldDayStaysIntact() throws Exception {
        LocalDate day = LocalDate.parse("2026-03-02");
        TestBucket bucket = TestBucket.shared();
        bucket.put(day, 1, List.of(renamed(SEP_01, day, SEQ_01)), TestBucket.Corruption.NONE);
        assertThat(fetch(TestStores.TIKI, day, FetchRequest.Mode.REPLACE).status()).isEqualTo(FetchRequest.Status.LOADED);
        long fileBefore = count("SELECT cpj_file_id FROM cpj.cpj_files WHERE store_id = ? AND business_date = ?", TestStores.TIKI, day);

        bucket.put(day, 2, List.of(renamed(SEP_02, day, SEQ_02)), TestBucket.Corruption.WRONG_SHA256);
        FetchRequest failed = fetch(TestStores.TIKI, day, FetchRequest.Mode.REPLACE);

        assertThat(failed.status()).isEqualTo(FetchRequest.Status.FAILED);
        assertThat(failed.message()).hasValueSatisfying(m -> assertThat(m).contains("sha256"));
        assertThat(count("SELECT cpj_file_id FROM cpj.cpj_files WHERE store_id = ? AND business_date = ?", TestStores.TIKI, day))
                .as("the old day is untouched").isEqualTo(fileBefore);
        assertThat(count("SELECT count(*) FROM cpj.tickets WHERE cpj_file_id = ?", fileBefore)).isEqualTo(537);
    }

    @Test
    void anInjectedUnknownElementFailsTheLoadNamingIt() throws Exception {
        LocalDate day = LocalDate.parse("2026-03-03");
        String xml = new String(TestBucket.realXml(SEP_01), StandardCharsets.UTF_8)
                .replaceFirst("<trValue>", "<trValue><trSurprise>1</trSurprise>");
        TestBucket.shared().put(day, 1, List.of(new TestBucket.Entry("CPJ" + day + "-" + SEQ_01 + ".xml",
                xml.getBytes(StandardCharsets.UTF_8))), TestBucket.Corruption.NONE);

        FetchRequest failed = fetch(TestStores.TIKI, day, FetchRequest.Mode.REPLACE);

        assertThat(failed.status()).isEqualTo(FetchRequest.Status.FAILED);
        assertThat(failed.message()).hasValueSatisfying(m -> assertThat(m).contains("unmapped element").contains("trValue/trSurprise"));
        assertThat(count("SELECT count(*) FROM cpj.cpj_files WHERE store_id = ? AND business_date = ?", TestStores.TIKI, day)).isZero();
    }

    @Test
    void aDayWithoutAZipIsNotFound() {
        FetchRequest notFound = fetch(TestStores.TIKI, LocalDate.parse("2026-03-04"), FetchRequest.Mode.REPLACE);

        assertThat(notFound.status()).isEqualTo(FetchRequest.Status.NOT_FOUND);
        assertThat(notFound.message()).hasValueSatisfying(m -> assertThat(m).contains("no CPJ zip"));
    }

    @Test
    void aZipWithTwoClosesLoadsBoth() throws Exception {
        LocalDate day = LocalDate.parse("2026-03-05");
        TestBucket.shared().put(day, 1, List.of(renamed(SEP_01, day, SEQ_01), renamed(SEP_02, day, SEQ_02)),
                TestBucket.Corruption.NONE);

        FetchRequest loaded = fetch(TestStores.TIKI, day, FetchRequest.Mode.REPLACE);

        assertThat(loaded.status()).as(loaded.message().orElse("")).isEqualTo(FetchRequest.Status.LOADED);
        assertThat(fetchQueries.detail(loaded.fetchRequestId()).files()).extracting(FetchQueries.LoadedFile::daySeq)
                .containsExactly(SEQ_01, SEQ_02);
        assertThat(count("""
                SELECT count(*) FROM cpj.tickets t JOIN cpj.cpj_files f USING (cpj_file_id)
                WHERE f.store_id = ? AND f.business_date = ?""", TestStores.TIKI, day)).isEqualTo(537 + 646);
    }

    @Test
    void theHighestVersionWinsComparedAsANumber() throws Exception {
        LocalDate day = LocalDate.parse("2026-03-06");
        TestBucket bucket = TestBucket.shared();
        bucket.put(day, 9, List.of(renamed(SEP_01, day, SEQ_01)), TestBucket.Corruption.NONE);
        bucket.put(day, 10, List.of(renamed(SEP_03, day, SEQ_03)), TestBucket.Corruption.NONE);

        FetchRequest loaded = fetch(TestStores.TIKI, day, FetchRequest.Mode.REPLACE);

        assertThat(fetchQueries.detail(loaded.fetchRequestId()).zip()).hasValueSatisfying(z -> assertThat(z.versionNo()).isEqualTo(10));
        assertThat(fetchQueries.detail(loaded.fetchRequestId()).files().get(0).daySeq()).isEqualTo(SEQ_03);
    }

    @Test
    void onlyActiveSyncedVerifoneStoresCanBeFetchedAndOthersAreNotFound() {
        // GILBARCO stores are never copied into master.stores, so they are simply unknown here.
        assertThatThrownBy(() -> fetches.start(TestStores.GILBARCO_STORE, SEP_01, FetchRequest.Mode.REPLACE, 1))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode().code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> fetches.start(TestStores.INACTIVE_STORE, SEP_01, FetchRequest.Mode.REPLACE, 1))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode().code()).isEqualTo("STORE_INACTIVE"));
        assertThatThrownBy(() -> fetches.start(999, SEP_01, FetchRequest.Mode.REPLACE, 1))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode().code()).isEqualTo("NOT_FOUND"));
    }

    private Map<String, Long> rows(FetchRequest fetch) {
        return fetchQueries.detail(fetch.fetchRequestId()).files().get(0).rows();
    }

    /** A real day's CPJ content filed under another (unused) business date, keeping its day number. */
    private static TestBucket.Entry renamed(LocalDate realDay, LocalDate asDay, int daySeq) {
        return new TestBucket.Entry("CPJ" + asDay + "-" + daySeq + ".xml", TestBucket.realXml(realDay));
    }
}
