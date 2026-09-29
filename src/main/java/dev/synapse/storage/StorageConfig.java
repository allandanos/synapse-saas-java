package dev.synapse.storage;

import dev.synapse.core.config.SynapseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** S3 when a bucket is configured; local disk otherwise (clone-and-run). */
@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean  // inferred destroy method closes the S3 client; the local backend has nothing to close
    StorageBackend storageBackend(SynapseProperties props) {
        if (props.s3Bucket() != null && !props.s3Bucket().isBlank()) {
            log.info("storage_backend backend=s3 bucket={}", props.s3Bucket());
            return new S3Storage(props.s3Bucket(), props.s3EndpointUrl(), props.s3Region(), props.s3AccessKeyId(),
                props.s3SecretAccessKey(), props.storagePresignSeconds());
        }
        log.info("storage_backend backend=local root={}", props.storageRoot());
        return new LocalDiskStorage(props.storageRoot());
    }
}
