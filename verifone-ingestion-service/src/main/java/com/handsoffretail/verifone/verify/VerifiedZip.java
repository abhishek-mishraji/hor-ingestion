package com.handsoffretail.verifone.verify;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipFile;

/** A downloaded zip whose manifest, paths, sizes and checksums were verified; its CPJ files in order. */
public record VerifiedZip(Path file, Manifest manifest, List<CpjEntry> cpjFiles) {

    public VerifiedZip {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(manifest, "manifest");
        cpjFiles = List.copyOf(cpjFiles);
    }

    /** Opens a CPJ file of this zip; each call reads it again (the parse and the count pass are separate). */
    public InputStream open(CpjEntry entry) throws IOException {
        ZipFile zip = new ZipFile(file.toFile());
        InputStream in = zip.getInputStream(zip.getEntry(entry.path()));
        return new FilterInputStream(in) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    zip.close();
                }
            }
        };
    }

    /** One CPJ XML: its manifest data and the date / day number from its name (CPJ2026-10-11-617.xml). */
    public record CpjEntry(Manifest.File manifestFile, LocalDate businessDate, int daySeq) {

        public String path() {
            return manifestFile.path();
        }
    }
}
