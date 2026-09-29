package dev.synapse.storage;

import dev.synapse.core.errors.NotFoundError;
import dev.synapse.core.errors.StorageError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Zero-config fallback: files under {@code SYNAPSE_STORAGE_ROOT/{org_id}/…}, no presigned URLs. */
public class LocalDiskStorage implements StorageBackend {

    private final Path root;

    public LocalDiskStorage(String storageRoot) {
        this.root = Path.of(storageRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new StorageError("Storage root is not writable: " + this.root);
        }
    }

    @Override
    public void put(String key, byte[] data, String contentType) {
        Path path = path(key);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, data);
        } catch (IOException e) {
            throw new StorageError("Could not write object: " + e.getMessage());
        }
    }

    @Override
    public byte[] get(String key) {
        Path path = path(key);
        if (!Files.isRegularFile(path)) {
            throw new NotFoundError("Object not found");
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new StorageError("Could not read object: " + e.getMessage());
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (IOException e) {
            throw new StorageError("Could not delete object: " + e.getMessage());
        }
    }

    @Override
    public String presignGet(String key) {
        path(key); // validation still applies
        throw new StorageError("Presigned URLs require an S3-compatible backend");
    }

    @Override
    public String presignPut(String key, String contentType) {
        path(key);
        throw new StorageError("Presigned URLs require an S3-compatible backend");
    }

    @Override
    public Long head(String key) {
        Path path = path(key);
        try {
            return Files.isRegularFile(path) ? Files.size(path) : null;
        } catch (IOException e) {
            throw new StorageError("Could not stat object: " + e.getMessage());
        }
    }

    @Override
    public boolean supportsPresignedUpload() {
        return false;
    }

    private Path path(String key) {
        StorageKeys.validate(key);
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new StorageError("Invalid storage key"); // traversal guard
        }
        return path;
    }
}
