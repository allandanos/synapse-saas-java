package dev.synapse.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.synapse.core.errors.NotFoundError;
import dev.synapse.core.errors.StorageError;
import dev.synapse.core.errors.TenantViolationError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Transliteration of the reference's {@code tests/unit/storage/test_keys_and_local.py}. */
class StorageKeysTest {

    @Nested
    class KeyValidation {

        @Test
        void validKey() {
            assertThatCode(() -> StorageKeys.validate(UUID.randomUUID() + "/reports/q1.pdf")).doesNotThrowAnyException();
        }

        @Test
        void rejectsEmptyAndGarbage() {
            assertThatThrownBy(() -> StorageKeys.validate("")).isInstanceOf(StorageError.class);
            assertThatThrownBy(() -> StorageKeys.validate("../etc/passwd")).isInstanceOf(StorageError.class);
            assertThatThrownBy(() -> StorageKeys.validate("has space/file.txt")).isInstanceOf(StorageError.class);
        }

        @Test
        void orgPrefixEnforced() {
            UUID org = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            assertThatThrownBy(() -> StorageKeys.validate(other + "/file.txt", org)).isInstanceOf(TenantViolationError.class);
        }

        @Test
        void scopedKeyBuildsValid() {
            UUID org = UUID.randomUUID();
            String key = StorageKeys.scoped(org, "reports/q1.pdf");
            assertThat(key).startsWith(org.toString());
            assertThatCode(() -> StorageKeys.validate(key, org)).doesNotThrowAnyException();
        }

        @Test
        void scopedKeyRejectsTraversal() {
            assertThatThrownBy(() -> StorageKeys.scoped(UUID.randomUUID(), "../../etc/passwd")).isInstanceOf(StorageError.class);
        }
    }

    @Nested
    class LocalDiskBackend {

        @Test
        void roundTrip(@TempDir Path tmp) {
            StorageBackend storage = new LocalDiskStorage(tmp.resolve("store").toString());
            String key = StorageKeys.scoped(UUID.randomUUID(), "docs/readme.txt");

            storage.put(key, "hello storage".getBytes(), "text/plain");
            assertThat(storage.get(key)).asString().isEqualTo("hello storage");
            assertThat(storage.head(key)).isEqualTo(13L);

            storage.delete(key);
            assertThatThrownBy(() -> storage.get(key)).isInstanceOf(NotFoundError.class);
            assertThat(storage.head(key)).isNull();
        }

        @Test
        void missingFileIs404(@TempDir Path tmp) {
            StorageBackend storage = new LocalDiskStorage(tmp.toString());
            assertThatThrownBy(() -> storage.get(UUID.randomUUID() + "/none.bin")).isInstanceOf(NotFoundError.class);
        }

        @Test
        void presignUnsupportedOnLocal(@TempDir Path tmp) {
            StorageBackend storage = new LocalDiskStorage(tmp.toString());
            assertThat(storage.supportsPresignedUpload()).isFalse();
            assertThatThrownBy(() -> storage.presignGet(UUID.randomUUID() + "/f"))
                .isInstanceOf(StorageError.class).hasMessageContaining("S3");
            assertThatThrownBy(() -> storage.presignPut(UUID.randomUUID() + "/f", "text/plain"))
                .isInstanceOf(StorageError.class).hasMessageContaining("S3");
        }

        /** A key that normalises outside the root never touches the filesystem. */
        @Test
        void traversalOutsideRootIsRefused(@TempDir Path tmp) throws IOException {
            Path root = tmp.resolve("store");
            Files.createDirectories(root);
            StorageBackend storage = new LocalDiskStorage(root.toString());
            assertThatThrownBy(() -> storage.get("a/../../escape.txt")).isInstanceOf(StorageError.class);
        }
    }
}
