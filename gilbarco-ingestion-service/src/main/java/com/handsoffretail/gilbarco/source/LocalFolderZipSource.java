package com.handsoffretail.gilbarco.source;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link ZipSource} on a local folder laid out exactly like the bucket (tests and offline work). The
 * ETag is the MD5 of the file, as S3 computes it for a single-part upload.
 */
public final class LocalFolderZipSource extends LayoutZipSource {

    private static final String SEPARATOR = "/";

    private final Path root;

    public LocalFolderZipSource(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String bucket() {
        return "local:" + root.toString().replace('\\', '/');
    }

    @Override
    protected List<String> listFolders(String prefix) {
        Path dir = resolve(prefix);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory).map(p -> p.getFileName() + SEPARATOR).sorted().toList();
        } catch (IOException e) {
            throw new ZipSourceException("cannot list " + dir, e);
        }
    }

    @Override
    protected List<StoredObject> listObjects(String prefix) {
        Path dir = resolve(prefix);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).sorted().map(this::stored).toList();
        } catch (IOException e) {
            throw new ZipSourceException("cannot list " + dir, e);
        }
    }

    @Override
    public void download(ZipObject zip, Path target) {
        try {
            Files.copy(resolve(zip.key()), target);
        } catch (IOException e) {
            throw new ZipSourceException("cannot copy " + zip.key(), e);
        }
    }

    private StoredObject stored(Path file) {
        try {
            String key = root.relativize(file).toString().replace('\\', '/');
            return new StoredObject(key, md5(file), Files.size(file), Files.getLastModifiedTime(file).toInstant());
        } catch (IOException e) {
            throw new ZipSourceException("cannot read " + file, e);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new ZipSourceException("key outside the local root: " + key);
        }
        return path;
    }

    private static String md5(Path file) throws IOException {
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("MD5"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is part of every Java runtime", e);
        }
    }
}
