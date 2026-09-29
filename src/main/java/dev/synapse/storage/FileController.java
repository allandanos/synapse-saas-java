package dev.synapse.storage;

import dev.synapse.core.context.RequestContextHolder;
import dev.synapse.core.context.TenantContext;
import dev.synapse.core.errors.StorageError;
import dev.synapse.core.pagination.Pagination;
import dev.synapse.core.web.RequireFeature;
import dev.synapse.core.web.RequirePermission;
import dev.synapse.storage.dto.FileRead;
import dev.synapse.storage.dto.PresignResponse;
import dev.synapse.storage.dto.PresignUploadRequest;
import dev.synapse.storage.dto.PresignUploadResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * {@code /v1/files}.
 *
 * <p>Upload is feature-gated on {@code api_access} (storage ships on paid
 * tiers) and meters {@code storage_bytes} against the plan quota — the same
 * enforcement path as every other metered resource. Reads need
 * {@code file:read}, writes {@code file:write}.
 */
@RestController
@RequestMapping("/v1/files")
public class FileController {

    private final FileService service;

    public FileController(FileService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermission("file:read")
    public List<FileRead> list(
            TenantContext tenant, HttpServletResponse response,
            @RequestParam(defaultValue = "" + Pagination.DEFAULT_PAGE_LIMIT) @Min(1) @Max(Pagination.MAX_PAGE_LIMIT) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        FileService.Page page = service.list(tenant.organizationId(), limit, offset);
        response.setHeader(Pagination.TOTAL_COUNT_HEADER, String.valueOf(page.total()));
        return page.rows().stream().map(FileRead::from).toList();
    }

    /** Direct upload (multipart, ≤10 MiB). Larger files use the presigned flow. */
    @PostMapping
    @RequirePermission("file:write")
    @RequireFeature("api_access")
    @ResponseStatus(HttpStatus.CREATED)
    public FileRead upload(HttpServletRequest request, TenantContext tenant) {
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/form-data")) {
            throw new StorageError("Expected multipart/form-data upload");
        }
        MultipartFile part = request instanceof MultipartHttpServletRequest multipart ? multipart.getFile("file") : null;
        if (part == null) {
            throw new StorageError("Missing 'file' part");
        }
        if (part.getSize() > FileService.MAX_DIRECT_UPLOAD_BYTES) {
            throw new StorageError("Direct upload capped at " + FileService.MAX_DIRECT_UPLOAD_BYTES / (1024 * 1024)
                + " MiB; use presigned upload");
        }
        byte[] data = bytesOf(part);
        UUID userId = RequestContextHolder.requireUser().userId();
        return FileRead.from(service.upload(tenant.organizationId(), userId, part.getOriginalFilename(),
            part.getContentType(), data));
    }

    @PostMapping("/presign-upload")
    @RequirePermission("file:write")
    @RequireFeature("api_access")
    public PresignUploadResponse presignUpload(@Valid @RequestBody PresignUploadRequest body, TenantContext tenant) {
        UUID userId = RequestContextHolder.requireUser().userId();
        FileService.Presigned presigned = service.presignUpload(tenant.organizationId(), userId, body.name(),
            body.contentTypeOrDefault(), body.sizeBytes());
        return PresignUploadResponse.of(presigned.file().id(), presigned.file().key(), presigned.url(),
            body.contentTypeOrDefault(), presigned.expiresIn());
    }

    @PostMapping("/{fileId}/complete")
    @RequirePermission("file:write")
    public FileRead complete(@PathVariable UUID fileId, TenantContext tenant) {
        return FileRead.from(service.completeUpload(fileId, tenant.organizationId()));
    }

    @GetMapping("/{fileId}")
    @RequirePermission("file:read")
    public ResponseEntity<byte[]> download(@PathVariable UUID fileId, TenantContext tenant) {
        FileService.Download download = service.download(fileId, tenant.organizationId());
        return ResponseEntity.ok()
            .contentType(mediaType(download.file().contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(download.file().name()).build().toString())
            .body(download.data());
    }

    /** Time-limited direct URL (S3 backends). */
    @PostMapping("/{fileId}/presign")
    @RequirePermission("file:read")
    public PresignResponse presignDownload(@PathVariable UUID fileId, TenantContext tenant) {
        FileService.Presigned presigned = service.presignDownload(fileId, tenant.organizationId());
        return new PresignResponse(presigned.url(), presigned.file().key(), presigned.expiresIn());
    }

    @DeleteMapping("/{fileId}")
    @RequirePermission("file:write")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID fileId, TenantContext tenant) {
        service.delete(fileId, tenant.organizationId());
    }

    private static byte[] bytesOf(MultipartFile part) {
        try {
            return part.getBytes();
        } catch (IOException e) {
            throw new StorageError("Could not read the uploaded part: " + e.getMessage());
        }
    }

    private static MediaType mediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
