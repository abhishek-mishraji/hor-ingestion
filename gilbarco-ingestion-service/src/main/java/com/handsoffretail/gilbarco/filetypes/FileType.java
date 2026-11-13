package com.handsoffretail.gilbarco.filetypes;

import java.util.Locale;

/** File type codes as the API receives them. */
public final class FileType {

    /** What the API assumes when a request names no file type (GILBARCO_SERVICE_PLAN.md D7). */
    public static final String DEFAULT = "PJR";

    private FileType() {
    }

    /** "pjr " to "PJR"; blank or missing to {@link #DEFAULT}. Whether the type is supported is the registry's business. */
    public static String normalize(String raw) {
        return raw == null || raw.isBlank() ? DEFAULT : raw.strip().toUpperCase(Locale.ROOT);
    }
}
