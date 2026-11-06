package com.metroticket_api.content;

import java.net.URI;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@ConditionalOnProperty(name = "app.storage.s3.enabled", havingValue = "true")
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final String bucket;

    public S3ObjectStorage(@Value("${app.storage.s3.endpoint}") String endpoint,
            @Value("${app.storage.s3.region}") String region,
            @Value("${app.storage.s3.bucket}") String bucket,
            @Value("${app.storage.s3.access-key}") String accessKey,
            @Value("${app.storage.s3.secret-key}") String secretKey) {
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        try {
            write(key, content, contentType);
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(bucket));
            write(key, content, contentType);
        }
    }

    private void write(String key, byte[] content, String contentType) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(content));
    }

    @Override
    public Optional<StoredObject> get(String key) {
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(b -> b.bucket(bucket).key(key));
            return Optional.of(new StoredObject(object.asByteArray(), object.response().contentType()));
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }
}
