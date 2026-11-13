package com.handsoffretail.gilbarco.filetypes;

import java.util.List;

import com.handsoffretail.gilbarco.verify.LoadCheck;

/**
 * One Gilbarco file type (PJR today; MCM, MSM, FGM, ... later): the only thing the core engine knows about a
 * format (GILBARCO_SERVICE_PLAN.md D1, open/closed). A new type is a new package under {@code filetypes},
 * its own schema migration and a {@code @Component} implementing this interface; nothing in {@code fetch},
 * {@code source}, {@code verify}, {@code stores} or another file type changes.
 */
public interface FileTypeProcessor {

    /** The type code in capitals, as in the S3 key and the manifest: "PJR". */
    String fileType();

    /** What is loaded of this type per store and day (REFUSE / REPLACE, calendars). */
    LoadedDays days();

    /**
     * Loads every file of a verified day package. Runs inside the caller's load transaction, after the day was
     * cleared (REPLACE) and the zip recorded; writes only to this type's own schema (and shared master data).
     *
     * @throws FileFormatException a file of the package cannot be understood (the whole day rolls back)
     */
    LoadOutcome processDay(FileLoadContext context);

    /** This type's load checks, run after {@link #processDay} together with the common ones. */
    List<? extends LoadCheck> supportedChecks();
}
