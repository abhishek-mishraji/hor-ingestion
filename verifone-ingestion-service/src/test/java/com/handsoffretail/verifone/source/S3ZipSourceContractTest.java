package com.handsoffretail.verifone.source;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import com.handsoffretail.verifone.TestEnv;

/**
 * The same contract against the real bucket, read only, with the owner's key from the secrets file.
 * Skipped (with a message) when the key is absent. Tiki Hut: 2025-09-26 has v1; 2025-09-01 has
 * nothing and never will (the history starts 2025-09-25). Both are in one past month on purpose: a
 * missing recent day can be uploaded later (2026-10-31 was, on 2026-11-17).
 */
class S3ZipSourceContractTest extends ZipSourceContract {

    private static S3ZipSource s3;

    @BeforeAll
    static void connect() {
        String key = TestEnv.get("HOR_AWS_ACCESS_KEY_ID").orElse(null);
        String secret = TestEnv.get("HOR_AWS_SECRET_ACCESS_KEY").orElse(null);
        Assumptions.assumeTrue(key != null && !key.isBlank() && secret != null && !secret.isBlank(),
                "no AWS key in the secrets file: real-S3 contract test skipped");
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
        return "Tiki_8730_IN-47872/Tiki_8730_IN-47872/";
    }

    @Override
    protected LocalDate dayWithZip() {
        return LocalDate.parse("2025-09-26");
    }

    @Override
    protected int expectedVersion() {
        return 1;
    }

    @Override
    protected LocalDate dayWithoutZip() {
        return LocalDate.parse("2025-09-01");
    }
}
