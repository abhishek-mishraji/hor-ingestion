package com.handsoffretail.verifone.verify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.springframework.stereotype.Component;

import com.handsoffretail.verifone.config.IngestionProperties;
import com.handsoffretail.verifone.source.ZipObject;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks a downloaded zip before anything is loaded (IMPLEMENTATION_PLAN.md Phase 4 step 2): the
 * manifest matches the key (type, business date, version, store), fileCount = files listed, no
 * absolute paths or "..", exactly the listed files are in the zip, every size and SHA-256 matches,
 * total uncompressed size within the limit, and every file is a CPJ XML of that business day.
 */
@Component
public class ManifestVerifier {

    static final String MANIFEST = "manifest.json";
    private static final String CPJ = "CPJ";
    private static final int MANIFEST_MAX_BYTES = 1 << 20;
    private static final int BUFFER = 1 << 16;
    private static final Pattern CPJ_FILE = Pattern.compile("CPJ(\\d{4}-\\d{2}-\\d{2})-(\\d+)\\.xml");
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
            requireSafePaths(manifest);
            requireExactlyListed(zip, manifest);
            requireChecksums(zip, manifest);
            return new VerifiedZip(file, manifest, cpjEntries(manifest, key));
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
        require(CPJ.equals(m.type()), "manifest type is " + m.type() + ", expected CPJ");
        require(key.businessDate().equals(m.businessDate()),
                "manifest businessDate " + m.businessDate() + " differs from the key's " + key.businessDate());
        require(m.version() == key.versionNo(), "manifest version " + m.version() + " differs from the key's v" + key.versionNo());
        require(m.store() != null && m.store().contains(storeCode) && key.fileName().startsWith(m.store() + "_" + CPJ + "_"),
                "manifest store " + m.store() + " does not match store " + storeCode + " and key " + key.fileName());
        require(m.createdAt() != null && m.fastMirrorVersion() != null && !m.fastMirrorVersion().isBlank(),
                "manifest createdAt / fastMirrorVersion missing");
        require(m.fileCount() == m.files().size(), "manifest fileCount " + m.fileCount() + " but " + m.files().size() + " files listed");
        require(!m.files().isEmpty(), "manifest lists no files");
    }

    private static void requireSafePaths(Manifest m) {
        Set<String> seen = new HashSet<>();
        for (Manifest.File f : m.files()) {
            String p = f.path();
            require(p != null && !p.isBlank(), "a manifest file has no path");
            require(!p.startsWith("/") && !p.contains("\\") && !p.contains("..") && !p.matches("^[A-Za-z]:.*")
                    && !p.equals(MANIFEST), "unsafe path in manifest: " + p);
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

    private static List<VerifiedZip.CpjEntry> cpjEntries(Manifest m, ZipObject key) {
        List<VerifiedZip.CpjEntry> entries = new ArrayList<>();
        for (Manifest.File f : m.files()) {
            String name = f.path().substring(f.path().lastIndexOf('/') + 1);
            Matcher matcher = CPJ_FILE.matcher(name);
            require(matcher.matches() && matcher.group(1).equals(key.businessDate().toString()),
                    f.path() + " is not a CPJ file of " + key.businessDate());
            entries.add(new VerifiedZip.CpjEntry(f, key.businessDate(), Integer.parseInt(matcher.group(2))));
        }
        entries.sort((a, b) -> Integer.compare(a.daySeq(), b.daySeq()));
        return entries;
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
