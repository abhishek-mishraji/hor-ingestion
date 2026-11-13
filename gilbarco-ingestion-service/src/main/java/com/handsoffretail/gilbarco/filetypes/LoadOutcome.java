package com.handsoffretail.gilbarco.filetypes;

import java.util.Map;
import java.util.Objects;

/** What a {@link FileTypeProcessor} wrote: rows per table, and a one-line summary for the fetch message. */
public record LoadOutcome(Map<String, Long> rowsWritten, String summary) {

    public LoadOutcome {
        rowsWritten = Map.copyOf(rowsWritten);
        Objects.requireNonNull(summary, "summary");
    }
}
