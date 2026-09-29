package dev.synapse.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Presigning is local SigV4 — no bucket, no network. Only the URL shape is
 * asserted: the signature itself is the SDK's business.
 */
class S3PresignTest {

    private static final String ENDPOINT = "http://localhost:9000";

    @Test
    void presignedGetIsPathStyleAndSigned() {
        try (S3Storage storage = new S3Storage("synapse-test", ENDPOINT, "us-east-1", "minio", "minio12345", 3600)) {
            String key = StorageKeys.scoped(UUID.randomUUID(), "reports/q1.pdf");
            URI url = URI.create(storage.presignGet(key));

            assertThat(url.getScheme()).isEqualTo("http");
            assertThat(url.getHost()).isEqualTo("localhost"); // a custom endpoint forces path style
            assertThat(url.getPath()).isEqualTo("/synapse-test/" + key);
            assertThat(url.getQuery())
                .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                .contains("X-Amz-Credential=minio")
                .contains("X-Amz-Expires=3600")
                .contains("X-Amz-Signature=");
            assertThat(storage.supportsPresignedUpload()).isTrue();
        }
    }

    @Test
    void presignedPutSignsTheContentType() {
        try (S3Storage storage = new S3Storage("synapse-test", ENDPOINT, "us-east-1", "minio", "minio12345", 900)) {
            String key = StorageKeys.scoped(UUID.randomUUID(), "big.bin");
            String url = storage.presignPut(key, "application/octet-stream");

            assertThat(url).contains("X-Amz-Expires=900").contains("X-Amz-SignedHeaders=content-type%3Bhost");
        }
    }

    /** AWS proper keeps virtual-host addressing: the bucket moves into the hostname. */
    @Test
    void awsEndpointStaysVirtualHosted() {
        try (S3Storage storage = new S3Storage("synapse-test", "", "ap-southeast-1", "AKIA000", "secret", 3600)) {
            URI url = URI.create(storage.presignGet(StorageKeys.scoped(UUID.randomUUID(), "a.txt")));
            assertThat(url.getHost()).isEqualTo("synapse-test.s3.ap-southeast-1.amazonaws.com");
        }
    }
}
