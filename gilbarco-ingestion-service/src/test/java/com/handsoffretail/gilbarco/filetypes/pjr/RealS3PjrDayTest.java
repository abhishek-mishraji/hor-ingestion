package com.handsoffretail.gilbarco.filetypes.pjr;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.handsoffretail.gilbarco.TestBucket;
import com.handsoffretail.gilbarco.TestEnv;
import com.handsoffretail.gilbarco.config.IngestionProperties;
import com.handsoffretail.gilbarco.filetypes.pjr.model.PjrRows;
import com.handsoffretail.gilbarco.filetypes.pjr.parser.PjrFileName;
import com.handsoffretail.gilbarco.filetypes.pjr.parser.PjrParser;
import com.handsoffretail.gilbarco.source.S3ZipSource;
import com.handsoffretail.gilbarco.source.ZipObject;
import com.handsoffretail.gilbarco.verify.Manifest;
import com.handsoffretail.gilbarco.verify.ManifestVerifier;
import com.handsoffretail.gilbarco.verify.VerifiedZip;

import tools.jackson.databind.json.JsonMapper;

/**
 * A real Wolf PJR day package from S3 (read only), without a database: the FastMirror manifest passes every
 * verifier rule and every file parses, as one event of the package's business day. Skipped without the AWS key.
 */
class RealS3PjrDayTest {

    private static final LocalDate DAY = LocalDate.parse("2026-10-15");   // a full day (2026-10-09, the first, holds 3 files)

    @TempDir
    Path dir;

    @Test
    void aRealDayPackageVerifiesAndEveryFileParses() throws IOException {
        String key = TestEnv.get("HOR_AWS_ACCESS_KEY_ID").orElse(null);
        String secret = TestEnv.get("HOR_AWS_SECRET_ACCESS_KEY").orElse(null);
        Assumptions.assumeTrue(key != null && secret != null, "no AWS key in the secrets file: real-S3 test skipped");
        VerifiedZip verified;
        try (S3ZipSource s3 = new S3ZipSource("hor-stores-data", "us-east-1", key, secret)) {
            ZipObject zip = s3.findLatestZip(TestBucket.STORE_FOLDER, DAY, "PJR").orElseThrow();
            Path file = dir.resolve("day.zip");
            s3.download(zip, file);
            verified = new ManifestVerifier(JsonMapper.builder().build(),
                    new IngestionProperties.Fetch(Duration.ofMinutes(30), 1, 1, 524_288_000)).verify(zip, file, TestBucket.STORE_CODE);
        }

        assertThat(verified.files()).hasSizeBetween(500, 1_200);
        PjrParser parser = new PjrParser();
        try (ZipFile zip = verified.open()) {
            for (Manifest.File f : verified.files()) {
                PjrFileName.of(f.fileName());
                try (InputStream in = new BufferedInputStream(zip.getInputStream(zip.getEntry(f.path())))) {
                    PjrRows.ParsedFile parsed = parser.parse(in, f.fileName());
                    assertThat(parsed.event().businessDate()).as(f.fileName()).isEqualTo(DAY);
                }
            }
        }
    }
}
