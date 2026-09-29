package dev.synapse.storage;

import dev.synapse.core.audit.AuditService;
import dev.synapse.core.errors.NotFoundError;
import dev.synapse.core.errors.PresignUnsupportedError;
import dev.synapse.core.errors.UploadIncompleteError;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxWriter;
import dev.synapse.usage.UsageService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * File storage service (reference: {@code storage/router.py}).
 *
 * <p>{@code storage_bytes} is a GAUGE (bytes currently stored), so capacity is
 * checked BEFORE a single byte is written and the level moves back down on
 * delete. The presigned flow reserves the quota up front and releases it when
 * the object never lands.
 */
@Service
public class FileService {

    /** Larger ⇒ presigned PUT (the reference's cap; enforced here, not by the container). */
    public static final long MAX_DIRECT_UPLOAD_BYTES = 10L * 1024 * 1024;
    public static final String STORAGE_METRIC = "storage_bytes";
    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final List<String> READY_ONLY = List.of(StoredFile.READY);
    private static final List<String> PENDING_OR_READY = List.of(StoredFile.PENDING, StoredFile.READY);

    private final StoredFileRepository files;
    private final StorageBackend storage;
    private final UsageService usage;
    private final SynapseProperties props;
    private final AuditService audit;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;

    public FileService(StoredFileRepository files, StorageBackend storage, UsageService usage, SynapseProperties props,
                       AuditService audit, OutboxWriter outbox, PlatformTransactionManager transactionManager) {
        this.files = files;
        this.storage = storage;
        this.usage = usage;
        this.props = props;
        this.audit = audit;
        this.outbox = outbox;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record Page(List<StoredFile> rows, long total) {}

    @Transactional(readOnly = true)
    public Page list(UUID organizationId, int limit, int offset) {
        return new Page(files.page(organizationId, limit, offset), files.count(organizationId));
    }

    /**
     * Direct upload. Order matters: the gauge moves FIRST (402 before a byte is
     * written), then the object, then the index row.
     */
    @Transactional
    public StoredFile upload(UUID organizationId, UUID userId, String filename, String contentType, byte[] data) {
        String name = filename == null || filename.isBlank() ? "unnamed" : filename;
        String type = contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType;
        usage.adjustGauge(organizationId, STORAGE_METRIC, data.length, true); // 402 on breach
        String key = StorageKeys.scoped(organizationId, name);
        storage.put(key, data, type);
        StoredFile row = files.insert(organizationId, key, name, type, data.length, StoredFile.READY, userId);
        announce(Events.FILE_UPLOADED, row);
        return row;
    }

    /**
     * Large-file path: reserve the quota, hand out a time-limited PUT URL, and
     * index the object as {@code pending}. Local-disk storage answers 409.
     */
    @Transactional
    public Presigned presignUpload(UUID organizationId, UUID userId, String name, String contentType, long sizeBytes) {
        requirePresignSupport("Presigned uploads need an S3-compatible backend; use multipart POST /files",
            Map.of("direct_upload_limit_bytes", MAX_DIRECT_UPLOAD_BYTES));
        // Reserved now (402 on breach) — released by complete-mismatch, delete, or retention
        usage.adjustGauge(organizationId, STORAGE_METRIC, sizeBytes, true);
        String key = StorageKeys.scoped(organizationId, name);
        String url = storage.presignPut(key, contentType);
        StoredFile row = files.insert(organizationId, key, name, contentType, sizeBytes, StoredFile.PENDING, userId);
        return new Presigned(row, url, props.storagePresignSeconds());
    }

    public record Presigned(StoredFile file, String url, int expiresIn) {}

    /**
     * Verify the uploaded object (exists, size matches the reservation) and mark
     * it ready. A mismatch releases the reservation, soft-deletes the row and
     * COMMITS before the 409 — the release must not roll back with the error.
     */
    public StoredFile completeUpload(UUID fileId, UUID organizationId) {
        StoredFile row = transactions.execute(tx -> scoped(fileId, organizationId, PENDING_OR_READY));
        if (StoredFile.READY.equals(row.status())) {
            return row; // idempotent
        }
        Long actual = storage.head(row.key());
        if (actual == null || actual != row.sizeBytes()) {
            transactions.executeWithoutResult(tx -> {
                usage.adjustGauge(organizationId, STORAGE_METRIC, -row.sizeBytes(), false);
                files.softDelete(row.id(), organizationId, Instant.now());
            });
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("expected_bytes", row.sizeBytes());
            extras.put("actual_bytes", actual);
            throw new UploadIncompleteError("Object missing or size mismatch; request a new presigned upload", extras);
        }
        return transactions.execute(tx -> {
            StoredFile ready = files.markReady(row.id(), organizationId);
            announce(Events.FILE_UPLOADED, ready);
            return ready;
        });
    }

    @Transactional(readOnly = true)
    public Download download(UUID fileId, UUID organizationId) {
        StoredFile row = scoped(fileId, organizationId, READY_ONLY);
        return new Download(row, storage.get(row.key()));
    }

    public record Download(StoredFile file, byte[] data) {}

    @Transactional(readOnly = true)
    public Presigned presignDownload(UUID fileId, UUID organizationId) {
        requirePresignSupport("Presigned URLs need an S3-compatible backend; download via GET /files/{id}", Map.of());
        StoredFile row = scoped(fileId, organizationId, READY_ONLY);
        return new Presigned(row, storage.presignGet(row.key()), props.storagePresignSeconds());
    }

    /** Soft-delete the index row, remove the object, and give the bytes back to the quota. */
    @Transactional
    public void delete(UUID fileId, UUID organizationId) {
        StoredFile row = scoped(fileId, organizationId, PENDING_OR_READY);
        files.softDelete(row.id(), organizationId, Instant.now());
        storage.delete(row.key());
        usage.adjustGauge(organizationId, STORAGE_METRIC, -row.sizeBytes(), false);
        announce(Events.FILE_DELETED, row);
    }

    /** One event through the outbox and one audit row, both inside the caller's transaction. */
    private void announce(String eventType, StoredFile row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file_id", row.id().toString());
        payload.put("name", row.name());
        payload.put("content_type", row.contentType());
        payload.put("size_bytes", row.sizeBytes());
        outbox.append(eventType, "file", row.id(), row.organizationId(), payload);
        audit.log(eventType, row.organizationId(), null, "file", row.id(), payload);
    }

    private void requirePresignSupport(String message, Map<String, Object> extras) {
        if (!storage.supportsPresignedUpload()) { // local disk has no presigned URLs in either direction
            throw new PresignUnsupportedError(message, extras);
        }
    }

    private StoredFile scoped(UUID fileId, UUID organizationId, List<String> statuses) {
        return files.findScoped(fileId, organizationId, statuses)
            .orElseThrow(() -> new NotFoundError("File not found")); // cross-tenant ⇒ same 404
    }
}
