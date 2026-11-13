package com.handsoffretail.verifone.load;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.handsoffretail.verifone.parse.handlers.CpjParser;
import com.handsoffretail.verifone.parse.model.ParseFacts;
import com.handsoffretail.verifone.verify.CheckContext;
import com.handsoffretail.verifone.verify.CheckResult;
import com.handsoffretail.verifone.verify.LoadVerifier;
import com.handsoffretail.verifone.verify.VerifiedZip;

/**
 * {@link DayLoader}: inside the caller's transaction, refuses or deletes the existing day, records the
 * zip, then for each CPJ file streams it through the {@link CpjParser} into a {@link CpjWriter} and
 * runs the {@link LoadVerifier}.
 */
@Component
class JdbcDayLoader implements DayLoader {

    private final JdbcTemplate jdbc;
    private final LoadedDays days;
    private final ZipRecords zips;
    private final LoadVerifier verifier;
    private final Clock clock;
    private final CpjParser parser = CpjParser.standard();

    JdbcDayLoader(JdbcTemplate jdbc, LoadedDays days, ZipRecords zips, LoadVerifier verifier, Clock clock) {
        this.jdbc = jdbc;
        this.days = days;
        this.zips = zips;
        this.verifier = verifier;
        this.clock = clock;
    }

    @Override
    public Outcome load(Request r) {
        long storeId = r.store().storeId();
        if (!r.replace() && days.isLoaded(storeId, r.businessDate())) {
            return new Refused(r.businessDate() + " is already loaded for " + r.store().storeCode() + " (mode REFUSE)");
        }
        if (r.replace()) {
            days.delete(storeId, r.businessDate());
        }
        long s3ObjectId = zips.save(storeId, r.bucket(), r.zip(), r.verified().manifest(), clock.instant());
        MasterCodeResolver codes = new MasterCodeResolver(jdbc, storeId);
        IdAllocator ids = new IdAllocator(jdbc);
        Set<String> flags = new HashSet<>(jdbc.queryForList("SELECT flag_code FROM cpj.line_flag_types", String.class));
        List<FileLoad> files = new ArrayList<>();
        for (VerifiedZip.CpjEntry entry : r.verified().cpjFiles()) {
            CpjWriter writer = new CpjWriter(jdbc, ids, codes, flags, new CpjWriter.Target(storeId, r.businessDate(),
                    entry.daySeq(), s3ObjectId, entry.path(), r.fetchRequestId()), clock);
            ParseFacts facts = parse(r.verified(), entry, writer);
            List<CheckResult> checks = verifier.verify(new CheckContext(writer.cpjFileId(), storeId, facts, r.verified(),
                    entry, writer.storedRegisterConflicts()));
            files.add(new FileLoad(writer.cpjFileId(), entry.path(), writer.rowsWritten(), checks));
        }
        return new Loaded(s3ObjectId, files);
    }

    private ParseFacts parse(VerifiedZip zip, VerifiedZip.CpjEntry entry, CpjWriter writer) {
        try (InputStream xml = zip.open(entry)) {
            return parser.parse(xml, writer);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + entry.path() + " from the zip", e);
        }
    }
}
