package com.handsoffretail.gilbarco.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The settings of gilbarco-ingestion-service, as records (one per prefix). */
public final class IngestionProperties {

    private IngestionProperties() {
    }

    /** Where zips come from: {@code s3} (production) or {@code local} (a folder laid out like the bucket). */
    public enum StorageMode {
        S3,
        LOCAL
    }

    /** {@code hor.storage.*}. */
    @ConfigurationProperties("hor.storage")
    public record Storage(@DefaultValue("S3") StorageMode mode, Path localRoot, Path workDir) {

        public Storage {
            Objects.requireNonNull(mode, "hor.storage.mode");
            if (mode == StorageMode.LOCAL && localRoot == null) {
                throw new IllegalArgumentException("hor.storage.local-root is required when hor.storage.mode=local");
            }
            workDir = workDir == null ? Path.of(System.getProperty("java.io.tmpdir"), "hor-gilbarco-ingestion") : workDir;
        }
    }

    /** {@code hor.s3.*}. */
    @ConfigurationProperties("hor.s3")
    public record S3(@DefaultValue("hor-stores-data") String bucket, @DefaultValue("us-east-1") String region) {
    }

    /** {@code hor.aws.*}: the read-only key from the secrets file. Never printed. */
    @ConfigurationProperties("hor.aws")
    public record Aws(String accessKeyId, String secretAccessKey) {

        public boolean isComplete() {
            return accessKeyId != null && !accessKeyId.isBlank() && secretAccessKey != null && !secretAccessKey.isBlank();
        }

        @Override
        public String toString() {
            return "Aws[key " + (isComplete() ? "set" : "not set") + "]";
        }
    }

    /** {@code hor.gilbarco.auth-db.*}: read-only connection to auth_db (core.stores), used by the store sync. */
    @ConfigurationProperties("hor.gilbarco.auth-db")
    public record AuthDb(String url, String username, String password) {

        public AuthDb {
            Objects.requireNonNull(url, "hor.gilbarco.auth-db.url");
            Objects.requireNonNull(username, "hor.gilbarco.auth-db.username");
        }

        @Override
        public String toString() {
            return "AuthDb[" + url + ", " + username + "]";
        }
    }

    /** {@code hor.fetch.*}. */
    @ConfigurationProperties("hor.fetch")
    public record Fetch(
            @DefaultValue("PT30M") Duration staleAfter,
            @DefaultValue("2") int workerThreads,
            @DefaultValue("20") int queueCapacity,
            @DefaultValue("524288000") long maxUncompressedBytes) {

        public Fetch {
            if (workerThreads < 1 || queueCapacity < 1 || maxUncompressedBytes < 1) {
                throw new IllegalArgumentException("hor.fetch worker-threads, queue-capacity and max-uncompressed-bytes must be positive");
            }
        }
    }
}
