package com.handsoffretail.gilbarco.source;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import com.handsoffretail.gilbarco.TestEnv;

/**
 * The same contract against the real bucket, read only, with the owner's key from the secrets file. Skipped
 * (with a message) when the key is absent. Wolf: the history starts 2026-10-09 (v1); 2026-10-11 has no PJR zip.
 */
class S3ZipSourceContractTest extends ZipSourceContract {

    private static S3ZipSource s3;

    @BeforeAll
    static void connect() {
        String key = TestEnv.get("HOR_AWS_ACCESS_KEY_ID").orElse(null);
        String secret = TestEnv.get("HOR_AWS_SECRET_ACCESS_KEY").orElse(null);
        Assumptions.assumeTrue(key != null && secret != null, "no AWS key in the secrets file: real-S3 contract test skipped");
        s3 = new S3ZipSource("hor-stores-data", "us-east-1", key, secret);
    }

    @AfterAll
    static void close() {
        if (s3 != null) {
            s3.close();
        }
    }

    @Override
    protected ZipSource source() {
        return s3;
    }

    @Override
    protected String storeFolder() {
        return "wolf_800_NY-13208/wolf_800_NY-13208/";
    }

    @Override
    protected LocalDate dayWithZip() {
        return LocalDate.parse("2026-10-09");
    }

    @Override
    protected int expectedVersion() {
        return 1;
    }

    @Override
    protected LocalDate dayWithoutZip() {
        return LocalDate.parse("2026-10-11");
    }
}
