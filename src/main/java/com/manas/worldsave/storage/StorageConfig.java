package com.manas.worldsave.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.nio.file.Path;

/**
 * Wires exactly one ObjectStore bean, chosen by worldsave.storage.backend
 * (filesystem or s3). Both implementations are always compiled and always
 * on the classpath; only the bean selection changes. Tests override this
 * property (and, for the s3 backend, the endpoint) to point at S3Mock.
 */
@Configuration
public class StorageConfig {

    @Bean
    @ConditionalOnProperty(prefix = "worldsave.storage", name = "backend", havingValue = "filesystem", matchIfMissing = true)
    public ObjectStore filesystemObjectStore(@Value("${worldsave.storage.filesystem.root-dir:./data/objectstore}") String rootDir) {
        return new FilesystemObjectStore(Path.of(rootDir));
    }

    @Bean
    @ConditionalOnProperty(prefix = "worldsave.storage", name = "backend", havingValue = "s3")
    public S3Client s3Client(
            @Value("${worldsave.storage.s3.endpoint}") String endpoint,
            @Value("${worldsave.storage.s3.region:us-east-1}") String region,
            @Value("${worldsave.storage.s3.access-key:test}") String accessKey,
            @Value("${worldsave.storage.s3.secret-key:test}") String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "worldsave.storage", name = "backend", havingValue = "s3")
    public ObjectStore s3ObjectStore(S3Client s3Client, @Value("${worldsave.storage.s3.bucket:world-saves}") String bucket) {
        software.amazon.awssdk.services.s3.model.HeadBucketRequest head =
                software.amazon.awssdk.services.s3.model.HeadBucketRequest.builder().bucket(bucket).build();
        try {
            s3Client.headBucket(head);
        } catch (software.amazon.awssdk.services.s3.model.NoSuchBucketException e) {
            s3Client.createBucket(builder -> builder.bucket(bucket));
        }
        return new S3ObjectStore(s3Client, bucket);
    }
}
