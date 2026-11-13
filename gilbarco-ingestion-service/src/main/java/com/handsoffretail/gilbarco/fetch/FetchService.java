package com.handsoffretail.gilbarco.fetch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.handsoffretail.gilbarco.common.GilbarcoErrorCode;
import com.handsoffretail.gilbarco.config.IngestionProperties;
import com.handsoffretail.gilbarco.filetypes.FileLoadContext;
import com.handsoffretail.gilbarco.filetypes.FileTypeProcessor;
import com.handsoffretail.gilbarco.filetypes.FileTypeRegistry;
import com.handsoffretail.gilbarco.filetypes.LoadOutcome;
import com.handsoffretail.gilbarco.source.GilbarcoKeys;
import com.handsoffretail.gilbarco.source.ZipObject;
import com.handsoffretail.gilbarco.source.ZipSource;
import com.handsoffretail.gilbarco.stores.StoreDirectory;
import com.handsoffretail.gilbarco.stores.StoreRef;
import com.handsoffretail.gilbarco.verify.CheckContext;
import com.handsoffretail.gilbarco.verify.LoadVerifier;
import com.handsoffretail.gilbarco.verify.ManifestVerifier;
import com.handsoffretail.gilbarco.verify.VerifiedZip;

/**
 * The fetch of one store's business day of one file type, the same for every type:
 * claim (own transaction) → RUNNING → find, download and verify the zip (no transaction, no locks) →
 * one load transaction (REFUSE / REPLACE, zip record, {@link FileTypeProcessor#processDay}, load checks, LOADED) →
 * or FAILED in its own transaction. This class owns those boundaries; what a file means is the processor's business.
 */
@Service
public class FetchService {

    private final StoreDirectory stores;
    private final FetchRepository fetches;
    private final FetchStatusRecorder recorder;
    private final FetchWorker worker;
    private final ZipSource source;
    private final ManifestVerifier manifestVerifier;
    private final FileTypeRegistry fileTypes;
    private final ZipRecords zips;
    private final LoadVerifier verifier;
    private final TransactionTemplate transactions;
    private final Path workDir;
    private final Clock clock;

    public FetchService(StoreDirectory stores, FetchRepository fetches, FetchStatusRecorder recorder, FetchWorker worker,
            ZipSource source, ManifestVerifier manifestVerifier, FileTypeRegistry fileTypes, ZipRecords zips,
            LoadVerifier verifier, TransactionTemplate transactions, IngestionProperties.Storage storage, Clock clock) {
        this.stores = stores;
        this.fetches = fetches;
        this.recorder = recorder;
        this.worker = worker;
        this.source = source;
        this.manifestVerifier = manifestVerifier;
        this.fileTypes = fileTypes;
        this.zips = zips;
        this.verifier = verifier;
        this.transactions = transactions;
        this.workDir = storage.workDir();
        this.clock = clock;
    }

    /**
     * Claims the day (409 if a fetch of it is open) and starts the fetch in the background.
     *
     * @param fileType PJR when null or blank; 422 UNSUPPORTED_FILE_TYPE when no processor handles it
     */
    public long start(long storeId, LocalDate businessDate, FetchRequest.Mode mode, String fileType, long adminId) {
        FileTypeProcessor processor = fileTypes.require(fileType);
        StoreRef store = stores.find(storeId).orElseThrow(() -> GilbarcoErrorCode.notFound("store", storeId));
        if (!store.isGilbarco()) {
            throw GilbarcoErrorCode.STORE_NOT_GILBARCO.exception("store " + store.storeCode() + " is not a Gilbarco store");
        }
        if (!store.active()) {
            throw GilbarcoErrorCode.STORE_INACTIVE.exception("store " + store.storeCode() + " is inactive");
        }
        if (businessDate.isAfter(LocalDate.now(clock.withZone(store.timezone())))) {
            throw GilbarcoErrorCode.DATE_IN_FUTURE.exception("the business date is in the future");
        }
        long fetchId = recorder.claim(store, businessDate, processor.fileType(), mode, adminId);
        worker.submit(fetchId, () -> process(fetchId));
        return fetchId;
    }

    /** Runs a claimed fetch to its end state (called on the fetch executor). */
    void process(long fetchId) {
        if (!recorder.start(fetchId)) {
            return;   // swept as stale while it waited in the queue
        }
        FetchRequest request = fetches.find(fetchId).orElseThrow(() -> GilbarcoErrorCode.notFound("fetch", fetchId));
        StoreRef store = stores.find(request.storeId())
                .orElseThrow(() -> GilbarcoErrorCode.notFound("store", request.storeId()));
        FileTypeProcessor processor = fileTypes.require(request.fileType());
        LocalDate day = request.businessDate();
        if (request.mode() == FetchRequest.Mode.REFUSE && processor.days().isLoaded(store.storeId(), day)) {
            recorder.end(fetchId, FetchRequest.Status.REFUSED, refusal(processor, day));
            return;
        }
        Optional<ZipObject> zip = source.findLatestZip(store.s3Folder(), day, processor.fileType());
        if (zip.isEmpty()) {
            recorder.end(fetchId, FetchRequest.Status.NOT_FOUND, "no " + processor.fileType() + " zip in " + source.bucket()
                    + " under " + store.s3Folder() + GilbarcoKeys.dayTypeFolder(day, processor.fileType()));
            return;
        }
        Path file = download(fetchId, zip.get());
        try {
            VerifiedZip verified = manifestVerifier.verify(zip.get(), file, store.storeCode());
            transactions.executeWithoutResult(status -> load(request, store, processor, zip.get(), verified));
        } finally {
            delete(file);
        }
    }

    /** The load transaction: the end state commits together with the data (or not at all). */
    private void load(FetchRequest request, StoreRef store, FileTypeProcessor processor, ZipObject zip, VerifiedZip verified) {
        long storeId = store.storeId();
        LocalDate day = request.businessDate();
        boolean replace = request.mode() == FetchRequest.Mode.REPLACE;
        if (!replace && processor.days().isLoaded(storeId, day)) {
            fetches.finish(request.fetchRequestId(), FetchRequest.Status.REFUSED, Optional.empty(), refusal(processor, day),
                    clock.instant());
            return;
        }
        if (replace) {
            processor.days().delete(storeId, day);
        }
        long s3ObjectId = zips.save(storeId, source.bucket(), zip, verified.manifest(), clock.instant());
        LoadOutcome outcome = processor.processDay(new FileLoadContext(request.fetchRequestId(), store, day, s3ObjectId,
                zip, verified));
        long softFailures = verifier.verify(new CheckContext(request.fetchRequestId(), store, day, processor.fileType(),
                s3ObjectId, verified, outcome.rowsWritten()), processor.supportedChecks())
                .stream().filter(c -> !c.passed()).count();
        fetches.finish(request.fetchRequestId(), FetchRequest.Status.LOADED, Optional.of(s3ObjectId), outcome.summary()
                + (softFailures == 0 ? "; all checks passed" : "; " + softFailures + " soft check(s) failed"), clock.instant());
    }

    private static String refusal(FileTypeProcessor processor, LocalDate day) {
        return processor.fileType() + " " + day + " is already loaded (mode REFUSE); nothing changed";
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
