package com.handsoffretail.gilbarco.verify;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipFile;

/**
 * A downloaded zip whose manifest, paths, sizes and checksums were verified, with its data files in path order.
 * File-type processors read the files through {@link #open()}: one {@link ZipFile} for the whole day.
 */
public record VerifiedZip(Path file, Manifest manifest, List<Manifest.File> files) {

    public VerifiedZip {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(manifest, "manifest");
        files = List.copyOf(files);
    }

    /** Opens the zip for reading its entries ({@code zip.getInputStream(zip.getEntry(file.path()))}); caller closes it. */
    public ZipFile open() throws IOException {
        return new ZipFile(file.toFile());
    }
}
