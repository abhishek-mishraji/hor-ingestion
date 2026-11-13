package com.handsoffretail.verifone.load;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.handsoffretail.verifone.source.ZipObject;
import com.handsoffretail.verifone.verify.Manifest;
import com.handsoffretail.verifone.verify.ZipRejectedException;

/**
 * ingest.s3_objects / s3_object_files. A zip already fetched before (same bucket, key and ETag:
 * a REPLACE of the same version) is reused, after checking its stored files equal the manifest.
 */
@Repository
class ZipRecords {

    private final JdbcClient jdbc;

    ZipRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long save(long storeId, String bucket, ZipObject zip, Manifest manifest, Instant now) {
        Optional<Long> known = jdbc.sql("SELECT s3_object_id FROM ingest.s3_objects WHERE bucket = :b AND object_key = :k AND etag = :e")
                .param("b", bucket).param("k", zip.key()).param("e", zip.etag()).query(Long.class).optional();
        if (known.isPresent()) {
            requireSameFiles(known.get(), manifest);
            return known.get();
        }
        long id = jdbc.sql("""
                INSERT INTO ingest.s3_objects (store_id, file_type, business_date, version_no, bucket, object_key, etag,
                    size_bytes, last_modified, manifest_created_at, mirror_version, downloaded_at)
                VALUES (:store, 'CPJ', :day, :version, :bucket, :key, :etag, :size, :modified, :created, :mirror, :now)
                RETURNING s3_object_id
                """)
                .param("store", storeId).param("day", zip.businessDate()).param("version", zip.versionNo())
                .param("bucket", bucket).param("key", zip.key()).param("etag", zip.etag()).param("size", zip.sizeBytes())
                .param("modified", utc(zip.lastModified())).param("created", utc(manifest.createdAt()))
                .param("mirror", manifest.fastMirrorVersion()).param("now", utc(now))
                .query(Long.class).single();
        for (Manifest.File f : manifest.files()) {
            jdbc.sql("""
                    INSERT INTO ingest.s3_object_files (s3_object_id, file_path, size_bytes, sha256, modified_at)
                    VALUES (:id, :path, :size, :sha, :modified)
                    """)
                    .param("id", id).param("path", f.path()).param("size", f.size()).param("sha", f.sha256())
                    .param("modified", utc(f.modified())).update();
        }
        return id;
    }

    private void requireSameFiles(long s3ObjectId, Manifest manifest) {
        List<String> stored = jdbc.sql("SELECT file_path || ' ' || sha256 FROM ingest.s3_object_files WHERE s3_object_id = :id ORDER BY 1")
                .param("id", s3ObjectId).query(String.class).list();
        List<String> listed = manifest.files().stream().map(f -> f.path() + " " + f.sha256()).sorted().toList();
        if (!stored.equals(listed)) {
            throw new ZipRejectedException("the zip has the same key and ETag as an earlier fetch but different files");
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
