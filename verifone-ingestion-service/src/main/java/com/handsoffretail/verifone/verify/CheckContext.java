package com.handsoffretail.verifone.verify;

import java.util.List;
import java.util.Objects;

import com.handsoffretail.verifone.parse.model.ParseFacts;

/** Everything a load check may look at: the stored file, what the parser saw, and the zip it came from. */
public record CheckContext(long cpjFileId, long storeId, ParseFacts facts, VerifiedZip zip, VerifiedZip.CpjEntry entry,
        List<String> storedRegisterConflicts) {

    public CheckContext {
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(zip, "zip");
        Objects.requireNonNull(entry, "entry");
        storedRegisterConflicts = List.copyOf(storedRegisterConflicts);
    }
}
