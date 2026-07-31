package com.journi.bench.s3;

import com.journi.bench.BenchConfig;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.time.Duration;

/**
 * Client setup mirrors core/service/.../AbstractS3Storage.scala:45-80 -
 * ApacheHttpClient + region + endpointOverride + apiCallTimeout, so connection
 * pool/timeout behavior matches production rather than SDK defaults.
 */
public final class S3Support {

    private S3Support() {}

    public static S3Client buildClient(BenchConfig cfg) {
        Duration timeout = Duration.ofSeconds(cfg.s3TimeoutSeconds());
        ClientOverrideConfiguration overrideConfig = ClientOverrideConfiguration.builder()
            .apiCallTimeout(timeout)
            .apiCallAttemptTimeout(timeout)
            .build();

        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(cfg.s3Region()))
            .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
            .httpClientBuilder(ApacheHttpClient.builder().maxConnections(cfg.s3MaxConnections()))
            .overrideConfiguration(overrideConfig)
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(cfg.s3PathStyle())
                .build());

        if (cfg.s3Endpoint() != null && !cfg.s3Endpoint().isBlank()) {
            builder = builder.endpointOverride(URI.create(cfg.s3Endpoint()));
        }
        return builder.build();
    }
}
