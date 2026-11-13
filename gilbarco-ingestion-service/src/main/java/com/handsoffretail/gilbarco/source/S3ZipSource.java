package com.handsoffretail.gilbarco.source;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/** {@link ZipSource} on the S3 bucket (AWS SDK v2, the read-only key from the secrets file). */
public final class S3ZipSource extends LayoutZipSource implements AutoCloseable {

    private static final String DELIMITER = "/";

    private final S3Client s3;
    private final String bucket;

    public S3ZipSource(String bucket, String region, String accessKeyId, String secretAccessKey) {
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    protected List<String> listFolders(String prefix) {
        List<String> folders = new ArrayList<>();
        for (ListObjectsV2Response page : pages(prefix, true)) {
            for (CommonPrefix common : page.commonPrefixes()) {
                folders.add(common.prefix().substring(prefix.length()));
            }
        }
        return folders;
    }

    @Override
    protected List<StoredObject> listObjects(String prefix) {
        List<StoredObject> objects = new ArrayList<>();
        for (ListObjectsV2Response page : pages(prefix, false)) {
            for (S3Object object : page.contents()) {
                objects.add(new StoredObject(object.key(), object.eTag().replace("\"", ""), object.size(),
                        object.lastModified()));
            }
        }
        return objects;
    }

    @Override
    public void download(ZipObject zip, Path target) {
        try {
            s3.getObject(GetObjectRequest.builder().bucket(bucket).key(zip.key()).ifMatch(quoted(zip.etag())).build(),
                    ResponseTransformer.toFile(target));
        } catch (SdkException e) {
            throw new ZipSourceException("cannot download s3://" + bucket + "/" + zip.key() + ": " + e.getMessage(), e);
        }
    }

    private Iterable<ListObjectsV2Response> pages(String prefix, boolean foldersOnly) {
        ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
        if (foldersOnly) {
            request.delimiter(DELIMITER);
        }
        try {
            return s3.listObjectsV2Paginator(request.build()).stream().toList();
        } catch (SdkException e) {
            throw new ZipSourceException("cannot list s3://" + bucket + "/" + prefix + ": " + e.getMessage(), e);
        }
    }

    private static String quoted(String etag) {
        return "\"" + etag + "\"";
    }

    @Override
    public void close() {
        s3.close();
    }
}
