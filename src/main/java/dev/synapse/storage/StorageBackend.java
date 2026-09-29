package dev.synapse.storage;

/**
 * One interface over both byte stores: local disk (zero-config) and anything
 * S3-compatible (AWS S3, Cloudflare R2, MinIO). Every method validates the key
 * first; presigning is optional and advertised by
 * {@link #supportsPresignedUpload()}.
 */
public interface StorageBackend {

    void put(String key, byte[] data, String contentType);

    /** The stored bytes; 404 {@code not_found} when the object is gone. */
    byte[] get(String key);

    void delete(String key);

    /** Time-limited download URL — the preferred way to serve files. */
    String presignGet(String key);

    /** Time-limited upload URL — large files bypass the API entirely. */
    String presignPut(String key, String contentType);

    /** Size of the stored object, or {@code null} when it does not exist (yet). */
    Long head(String key);

    boolean supportsPresignedUpload();
}
