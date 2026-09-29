package dev.synapse.storage;

import dev.synapse.core.errors.NotFoundError;
import dev.synapse.core.errors.StorageError;
import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Any S3-compatible target: AWS S3, Cloudflare R2, MinIO
 * (reference: {@code storage/backend.py:S3Storage}).
 *
 * <p>A custom endpoint (MinIO/R2) forces path-style addressing, because those
 * hosts do not resolve {@code bucket.host}. Presigned URLs are SigV4 from the
 * SDK's own presigner, so they are valid against the same endpoint the client sees.
 */
public class S3Storage implements StorageBackend, AutoCloseable {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration expiry;

    public S3Storage(String bucket, String endpointUrl, String region, String accessKeyId, String secretAccessKey,
                     int presignSeconds) {
        if (bucket == null || bucket.isBlank()) {
            throw new StorageError("SYNAPSE_S3_BUCKET is not configured");
        }
        this.bucket = bucket;
        this.expiry = Duration.ofSeconds(presignSeconds);
        boolean custom = endpointUrl != null && !endpointUrl.isBlank();
        Region awsRegion = Region.of(region == null || region.isBlank() ? "us-east-1" : region);
        var credentials = (accessKeyId == null || accessKeyId.isBlank())
            ? DefaultCredentialsProvider.create()
            : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey));
        S3Configuration serviceConfig = S3Configuration.builder().pathStyleAccessEnabled(custom).build();

        var clientBuilder = S3Client.builder().region(awsRegion).credentialsProvider(credentials)
            .serviceConfiguration(serviceConfig);
        var presignerBuilder = S3Presigner.builder().region(awsRegion).credentialsProvider(credentials)
            .serviceConfiguration(serviceConfig);
        if (custom) {
            clientBuilder.endpointOverride(URI.create(endpointUrl));
            presignerBuilder.endpointOverride(URI.create(endpointUrl));
        }
        this.client = clientBuilder.build();
        this.presigner = presignerBuilder.build();
    }

    @Override
    public void put(String key, byte[] data, String contentType) {
        StorageKeys.validate(key);
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(data));
        } catch (S3Exception e) {
            throw new StorageError("S3 put failed: " + e.getMessage());
        }
    }

    @Override
    public byte[] get(String key) {
        StorageKeys.validate(key);
        try {
            return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new NotFoundError("Object not found");
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new NotFoundError("Object not found");
            }
            throw new StorageError("S3 get failed: " + e.getMessage());
        }
    }

    @Override
    public void delete(String key) {
        StorageKeys.validate(key);
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception e) {
            throw new StorageError("S3 delete failed: " + e.getMessage());
        }
    }

    @Override
    public String presignGet(String key) {
        StorageKeys.validate(key);
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
            .signatureDuration(expiry)
            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
            .build()).url().toString();
    }

    @Override
    public String presignPut(String key, String contentType) {
        StorageKeys.validate(key);
        return presigner.presignPutObject(PutObjectPresignRequest.builder()
            .signatureDuration(expiry)
            .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build())
            .build()).url().toString();
    }

    @Override
    public Long head(String key) {
        StorageKeys.validate(key);
        try {
            return client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength();
        } catch (NoSuchKeyException e) {
            return null;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            throw new StorageError("S3 head failed: " + e.getMessage());
        }
    }

    @Override
    public boolean supportsPresignedUpload() {
        return true;
    }

    @Override
    public void close() {
        presigner.close();
        client.close();
    }
}
