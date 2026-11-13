package com.handsoffretail.gilbarco.verify;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** manifest.json of a FastMirror day package: what was packed, with each file's size and SHA-256. */
public record Manifest(String store, String type, LocalDate businessDate, int version, Instant createdAt,
        String fastMirrorVersion, int fileCount, List<File> files) {

    public Manifest {
        files = files == null ? List.of() : List.copyOf(files);
    }

    /** One listed file. */
    public record File(String path, long size, String sha256, Instant modified) {

        /** The original POS file name (last path segment). */
        public String fileName() {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }
}
