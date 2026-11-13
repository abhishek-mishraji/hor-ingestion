package com.handsoffretail.gilbarco.verify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.springframework.stereotype.Component;

import com.handsoffretail.gilbarco.config.IngestionProperties;
import com.handsoffretail.gilbarco.source.GilbarcoKeys;
import com.handsoffretail.gilbarco.source.ZipObject;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks a downloaded day package before anything is loaded, for any file type (DAY_PACKAGES_Zip.md section 8):
 * the manifest matches the key (type, business date, version, store), fileCount = files listed, no absolute
 * paths or "..", every path is {@code <yyyy>/<month>/<businessDate>/<TYPE>/<file name>}, exactly the listed files
 * are in the zip, every size and SHA-256 matches, and the total uncompressed size is within the limit.
 * What a file of the type must contain is the file-type processor's business.
 */
@Component
public class ManifestVerifier {

    static final String MANIFEST = "manifest.json";
    private static final int MANIFEST_MAX_BYTES = 4 << 20;
    private static final int BUFFER = 1 << 16;
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private final ObjectReader manifestReader;
    private final long maxUncompressedBytes;

    public ManifestVerifier(JsonMapper json, IngestionProperties.Fetch fetch) {
        this.manifestReader = json.readerFor(Manifest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.maxUncompressedBytes = fetch.maxUncompressedBytes();
    }

    public VerifiedZip verify(ZipObject key, Path file, String storeCode) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Manifest manifest = readManifest(zip);
            matchesKey(manifest, key, storeCode);
            requireSafePaths(manifest, key);
            requireExactlyListed(zip, manifest);
            requireChecksums(zip, manifest);
            return new VerifiedZip(file, manifest,
                    manifest.files().stream().sorted(Comparator.comparing(Manifest.File::path)).toList());
        } catch (IOException e) {
            throw new ZipRejectedException("not a readable zip: " + e.getMessage(), e);
        }
    }

    private Manifest readManifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(MANIFEST);
        if (entry == null) {
            throw new ZipRejectedException("no " + MANIFEST + " in the zip");
        }
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(MANIFEST_MAX_BYTES + 1);
            if (bytes.length > MANIFEST_MAX_BYTES) {
                throw new ZipRejectedException(MANIFEST + " is larger than " + MANIFEST_MAX_BYTES + " bytes");
            }
            return manifestReader.readValue(bytes);
        } catch (JacksonException e) {
            throw new ZipRejectedException(MANIFEST + " is not valid: " + e.getOriginalMessage(), e);
        }
    }

    private static void matchesKey(Manifest m, ZipObject key, String storeCode) {
        require(key.fileType().equals(m.type()), "manifest type is " + m.type() + ", expected " + key.fileType());
        require(key.businessDate().equals(m.businessDate()),
                "manifest businessDate " + m.businessDate() + " differs from the key's " + key.businessDate());
        require(m.version() == key.versionNo(), "manifest version " + m.version() + " differs from the key's v" + key.versionNo());
        require(m.store() != null && m.store().contains(storeCode)
                        && key.fileName().startsWith(m.store() + "_" + key.fileType() + "_"),
                "manifest store " + m.store() + " does not match store " + storeCode + " and key " + key.fileName());
        require(m.createdAt() != null && m.fastMirrorVersion() != null && !m.fastMirrorVersion().isBlank(),
                "manifest createdAt / fastMirrorVersion missing");
        require(m.fileCount() == m.files().size(), "manifest fileCount " + m.fileCount() + " but " + m.files().size() + " files listed");
        require(!m.files().isEmpty(), "manifest lists no files");
    }

    private static void requireSafePaths(Manifest m, ZipObject key) {
        String folder = GilbarcoKeys.dayTypeFolder(key.businessDate(), key.fileType());
        Set<String> seen = new HashSet<>();
        for (Manifest.File f : m.files()) {
            String p = f.path();
            require(p != null && !p.isBlank(), "a manifest file has no path");
            require(!p.startsWith("/") && !p.contains("\\") && !p.contains("..") && !p.matches("^[A-Za-z]:.*")
                    && !p.equals(MANIFEST), "unsafe path in manifest: " + p);
            require(p.regionMatches(true, 0, folder, 0, folder.length()) && p.indexOf('/', folder.length()) < 0
                    && p.length() > folder.length(), p + " is not a file directly under " + folder);
            require(seen.add(p), "path listed twice in manifest: " + p);
            require(f.size() >= 0 && f.sha256() != null && SHA256.matcher(f.sha256()).matches() && f.modified() != null,
                    "size / sha256 / modified missing or malformed for " + p);
        }
    }

    private static void requireExactlyListed(ZipFile zip, Manifest m) {
        Set<String> listed = new HashSet<>();
        m.files().forEach(f -> listed.add(f.path()));
        Set<String> inZip = new HashSet<>();
        for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && !entry.getName().equals(MANIFEST)) {
                require(listed.contains(entry.getName()), "file in the zip but not in the manifest: " + entry.getName());
                inZip.add(entry.getName());
            }
        }
        listed.removeAll(inZip);
        require(listed.isEmpty(), "files in the manifest but not in the zip: " + listed);
    }

    private void requireChecksums(ZipFile zip, Manifest m) throws IOException {
        long total = 0;
        byte[] buffer = new byte[BUFFER];
        for (Manifest.File f : m.files()) {
            MessageDigest sha = sha256();
            long size = 0;
            try (InputStream in = zip.getInputStream(zip.getEntry(f.path()))) {
                for (int n; (n = in.read(buffer)) > 0;) {
                    sha.update(buffer, 0, n);
                    size += n;
                    total += n;
                    require(total <= maxUncompressedBytes, "uncompressed size over " + maxUncompressedBytes + " bytes");
                }
            }
            require(size == f.size(), f.path() + " is " + size + " bytes, the manifest says " + f.size());
            require(HexFormat.of().formatHex(sha.digest()).equals(f.sha256()), "sha256 of " + f.path() + " does not match the manifest");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw new ZipRejectedException(reason);
        }
    }
}
