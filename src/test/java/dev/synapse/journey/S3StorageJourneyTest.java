package dev.synapse.journey;

import static dev.synapse.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.support.ApiClient;
import dev.synapse.support.ApiClient.Res;
import dev.synapse.support.ApiClient.Tenant;
import dev.synapse.support.PostgresTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * The S3 code path end to end against MinIO: presigned upload → the client's
 * own PUT → complete → ready, and completing without a PUT releasing the
 * reservation. Skipped when Docker cannot start MinIO; the local-disk journey
 * covers everything that does not need a bucket.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "synapse.bootstrap-admin-email=" + ApiJourneyTest.ADMIN_EMAIL,
    "synapse.bootstrap-admin-password=" + ApiJourneyTest.ADMIN_PASSWORD,
    "synapse.worker-enabled=false"
})
class S3StorageJourneyTest extends PostgresTestSupport {

    /** {@code SYNAPSE_TEST_S3_ENDPOINT} points at an already-running MinIO; otherwise Testcontainers starts one. */
    private static final String EXTERNAL_ENDPOINT = System.getenv("SYNAPSE_TEST_S3_ENDPOINT");
    static final String BUCKET = "synapse-java-journey";
    static final String USER = env("SYNAPSE_TEST_S3_ACCESS_KEY_ID", "minio");
    static final String PASSWORD = env("SYNAPSE_TEST_S3_SECRET_ACCESS_KEY", "minio12345");

    private static GenericContainer<?> minio;
    private static String endpoint;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        if (endpoint == null) {
            endpoint = EXTERNAL_ENDPOINT != null && !EXTERNAL_ENDPOINT.isBlank() ? EXTERNAL_ENDPOINT : startMinio();
            if (endpoint != null) {
                createBucket();
            }
        }
        if (endpoint != null) {
            registry.add("synapse.s3-endpoint-url", () -> endpoint);
            registry.add("synapse.s3-bucket", () -> BUCKET);
            registry.add("synapse.s3-access-key-id", () -> USER);
            registry.add("synapse.s3-secret-access-key", () -> PASSWORD);
            registry.add("synapse.s3-region", () -> "us-east-1");
            registry.add("synapse.storage-presign-seconds", () -> 600);
        }
    }

    private static String startMinio() {
        try {
            minio = new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:latest"))
                .withEnv("MINIO_ROOT_USER", USER)
                .withEnv("MINIO_ROOT_PASSWORD", PASSWORD)
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).withStartupTimeout(Duration.ofSeconds(60)));
            minio.start();
            return "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        } catch (RuntimeException e) {
            minio = null;
            return null; // no Docker ⇒ the S3 journeys skip; the local-disk journey still runs
        }
    }

    /** Idempotent: an existing bucket is fine. */
    private static void createBucket() {
        try (S3Client client = S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(USER, PASSWORD)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException ignored) {
            // already provisioned
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    ApiClient api;

    @BeforeEach
    void setUp() {
        assumeTrue(endpoint != null, "no MinIO: set SYNAPSE_TEST_S3_ENDPOINT or make Docker reachable");
        api = new ApiClient(mvc, json);
    }

    @Test
    void presignedUploadBecomesReadyOnlyAfterTheBytesLand() throws Exception {
        Tenant tenant = api.makeTenant("s3");
        byte[] payload = "0123456789".repeat(64).getBytes(StandardCharsets.UTF_8); // 640 bytes

        Res presigned = api.post("/v1/files/presign-upload", tenant.headers(),
            Map.of("name", "big.bin", "size_bytes", payload.length, "content_type", "application/octet-stream"));
        assertThat(presigned.status()).isEqualTo(200);
        assertThat(presigned.text("method")).isEqualTo("PUT");
        assertThat(presigned.body().at("/headers/Content-Type").asText()).isEqualTo("application/octet-stream");
        assertThat(presigned.body().get("expires_in").asInt()).isEqualTo(600);
        assertThat(presigned.text("url")).startsWith("http");
        assertThat(presigned.text("key")).isEqualTo(tenant.orgId() + "/big.bin");
        String fileId = presigned.text("id");
        // Reserved up front, and invisible until it completes
        assertThat(used(tenant)).isEqualTo(payload.length);
        assertThat(api.get("/v1/files", tenant.headers()).body().size()).isZero();

        // Completing before the object exists releases the reservation and says so
        JsonNode incomplete = assertProblem(api.post("/v1/files/" + fileId + "/complete", tenant.headers(), null),
            409, "upload incomplete");
        assertThat(incomplete.get("expected_bytes").asLong()).isEqualTo(payload.length);
        assertThat(incomplete.get("actual_bytes").isNull()).isTrue();
        assertThat(used(tenant)).as("the release survives the error").isZero();
        assertThat(jdbc.sql("SELECT deleted_at IS NOT NULL FROM stored_files WHERE id = :id")
            .param("id", java.util.UUID.fromString(fileId)).query(Boolean.class).single()).isTrue();

        // Now the real thing: reserve, PUT to the presigned URL, complete
        Res second = api.post("/v1/files/presign-upload", tenant.headers(),
            Map.of("name", "big.bin", "size_bytes", payload.length, "content_type", "application/octet-stream"));
        assertThat(second.status()).isEqualTo(200);
        assertThat(put(second.text("url"), payload, "application/octet-stream")).isBetween(200, 299);

        Res completed = api.post("/v1/files/" + second.text("id") + "/complete", tenant.headers(), null);
        assertThat(completed.status()).isEqualTo(200);
        assertThat(completed.text("status")).isEqualTo("ready");
        // Idempotent
        assertThat(api.post("/v1/files/" + second.text("id") + "/complete", tenant.headers(), null).text("status"))
            .isEqualTo("ready");

        MockHttpServletResponse download = api.raw(HttpMethod.GET, "/v1/files/" + second.text("id"), tenant.headers());
        assertThat(download.getStatus()).isEqualTo(200);
        assertThat(download.getContentAsByteArray()).isEqualTo(payload);

        Res presignGet = api.post("/v1/files/" + second.text("id") + "/presign", tenant.headers(), null);
        assertThat(presignGet.status()).isEqualTo(200);
        assertThat(presignGet.text("url")).startsWith("http").contains("X-Amz-Signature=");
        assertThat(presignGet.text("key")).isEqualTo(tenant.orgId() + "/big.bin");

        assertThat(api.delete("/v1/files/" + second.text("id"), tenant.headers()).status()).isEqualTo(204);
        assertThat(used(tenant)).isZero();
    }

    @Test
    void directMultipartUploadsStillWorkAgainstTheBucket() throws Exception {
        Tenant tenant = api.makeTenant("s3-direct");
        MockMultipartHttpServletRequestBuilder builder = multipart("/v1/files");
        builder.file(new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)));
        tenant.headers().forEach(builder::header);
        MockHttpServletResponse raw = mvc.perform(builder).andReturn().getResponse();
        assertThat(raw.getStatus()).isEqualTo(201);
        String fileId = json.readTree(raw.getContentAsString()).get("id").asText();

        MockHttpServletResponse download = api.raw(HttpMethod.GET, "/v1/files/" + fileId, tenant.headers());
        assertThat(download.getContentAsByteArray()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(api.delete("/v1/files/" + fileId, tenant.headers()).status()).isEqualTo(204);
    }

    private static int put(String url, byte[] body, String contentType) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    private long used(Tenant tenant) throws Exception {
        Res res = api.get("/v1/usage/summary", tenant.headers());
        assertThat(res.status()).isEqualTo(200);
        for (JsonNode metric : res.body().get("metrics")) {
            if ("storage_bytes".equals(metric.get("metric").asText())) {
                return metric.get("used").asLong();
            }
        }
        return 0;
    }
}
