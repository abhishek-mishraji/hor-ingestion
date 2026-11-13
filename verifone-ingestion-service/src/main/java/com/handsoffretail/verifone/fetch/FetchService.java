package com.handsoffretail.verifone.fetch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.handsoffretail.verifone.common.VerifoneErrorCode;
import com.handsoffretail.verifone.config.IngestionProperties;
import com.handsoffretail.verifone.load.DayLoader;
import com.handsoffretail.verifone.load.LoadedDays;
import com.handsoffretail.verifone.source.ZipObject;
import com.handsoffretail.verifone.source.ZipSource;
import com.handsoffretail.verifone.stores.StoreDirectory;
import com.handsoffretail.verifone.stores.StoreRef;
import com.handsoffretail.verifone.verify.ManifestVerifier;
import com.handsoffretail.verifone.verify.VerifiedZip;

/**
 * The fetch of one business day:
 * claim (own transaction) → find and download the zip and verify it (no transaction, no locks) →
 * one load transaction (REFUSE / REPLACE, rows, load checks, LOADED) → or FAILED in its own
 * transaction. This class owns those boundaries and nothing else.
 */
@Service
public class FetchService {

    private final StoreDirectory stores;
    private final FetchRepository fetches;
    private final FetchStatusRecorder recorder;
    private final FetchWorker worker;
    private final ZipSource source;
    private final ManifestVerifier manifestVerifier;
    private final DayLoader loader;
    private final LoadedDays loadedDays;
    private final TransactionTemplate transactions;
    private final Path workDir;
    private final Clock clock;

    public FetchService(StoreDirectory stores, FetchRepository fetches, FetchStatusRecorder recorder, FetchWorker worker,
            ZipSource source, ManifestVerifier manifestVerifier, DayLoader loader, LoadedDays loadedDays,
            TransactionTemplate transactions, IngestionProperties.Storage storage, Clock clock) {
        this.stores = stores;
        this.fetches = fetches;
        this.recorder = recorder;
        this.worker = worker;
        this.source = source;
        this.manifestVerifier = manifestVerifier;
        this.loader = loader;
        this.loadedDays = loadedDays;
        this.transactions = transactions;
        this.workDir = storage.workDir();
        this.clock = clock;
    }

    /** Claims the day (409 if a fetch of it is running) and starts the fetch in the background. */
    public long start(long storeId, LocalDate businessDate, FetchRequest.Mode mode, long adminId) {
        StoreRef store = stores.find(storeId).orElseThrow(() -> VerifoneErrorCode.notFound("store", storeId));
        if (!store.isVerifone()) {
            throw VerifoneErrorCode.STORE_NOT_VERIFONE.exception("store " + store.storeCode() + " is not a Verifone store");
        }
        if (!store.active()) {
            throw VerifoneErrorCode.STORE_INACTIVE.exception("store " + store.storeCode() + " is inactive");
        }
        if (businessDate.isAfter(LocalDate.now(clock.withZone(store.timezone())))) {
            throw VerifoneErrorCode.DATE_IN_FUTURE.exception("the business date is in the future");
        }
        long fetchId = recorder.claim(store, businessDate, mode, adminId);
        worker.submit(fetchId, () -> process(fetchId));
        return fetchId;
    }

    /** Runs a claimed fetch to its end state (called on the fetch executor). */
    void process(long fetchId) {
        FetchRequest request = fetches.find(fetchId).orElseThrow(() -> VerifoneErrorCode.notFound("fetch", fetchId));
        StoreRef store = stores.find(request.storeId())
                .orElseThrow(() -> VerifoneErrorCode.notFound("store", request.storeId()));
        LocalDate day = request.businessDate();
        if (request.mode() == FetchRequest.Mode.REFUSE && loadedDays.isLoaded(store.storeId(), day)) {
            recorder.end(fetchId, FetchRequest.Status.REFUSED, day + " is already loaded (mode REFUSE); nothing changed");
            return;
        }
        Optional<ZipObject> zip = source.findLatestCpjZip(store.s3Folder(), day);
        if (zip.isEmpty()) {
            recorder.end(fetchId, FetchRequest.Status.NOT_FOUND, "no CPJ zip in " + source.bucket() + " under "
                    + store.s3Folder() + day.getYear() + "/<month>/" + day + "/CPJ/");
            return;
        }
        Path file = download(fetchId, zip.get());
        try {
            VerifiedZip verified = manifestVerifier.verify(zip.get(), file, store.storeCode());
            DayLoader.Request load = new DayLoader.Request(fetchId, store, day, request.mode() == FetchRequest.Mode.REPLACE,
                    source.bucket(), zip.get(), verified);
            transactions.executeWithoutResult(status -> finish(fetchId, loader.load(load)));
        } finally {
            delete(file);
        }
    }

    /** In the load transaction: the end state commits together with the data (or not at all). */
    private void finish(long fetchId, DayLoader.Outcome outcome) {
        switch (outcome) {
            case DayLoader.Refused refused -> fetches.finish(fetchId, FetchRequest.Status.REFUSED, Optional.empty(),
                    refused.reason() + "; nothing changed", clock.instant());
            case DayLoader.Loaded loaded -> fetches.finish(fetchId, FetchRequest.Status.LOADED,
                    Optional.of(loaded.s3ObjectId()), summary(loaded), clock.instant());
        }
    }

    private static String summary(DayLoader.Loaded loaded) {
        StringBuilder text = new StringBuilder("loaded ").append(loaded.files().size()).append(" file(s): ");
        for (DayLoader.FileLoad file : loaded.files()) {
            long soft = file.checks().stream().filter(c -> !c.passed()).count();
            text.append(file.filePath().substring(file.filePath().lastIndexOf('/') + 1)).append(" (")
                    .append(file.rows().getOrDefault("cpj.tickets", 0)).append(" tickets")
                    .append(soft == 0 ? ", all checks passed" : ", " + soft + " soft check(s) failed").append(") ");
        }
        return text.toString().strip();
    }

    private Path download(long fetchId, ZipObject zip) {
        try {
            Files.createDirectories(workDir);
            Path file = workDir.resolve("fetch-" + fetchId + ".zip");
            Files.deleteIfExists(file);
            source.download(zip, file);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot prepare the work folder " + workDir, e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + file, e);
        }
    }
}
