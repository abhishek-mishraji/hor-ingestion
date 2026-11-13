package com.handsoffretail.verifone.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.handsoffretail.verifone.source.LocalFolderZipSource;
import com.handsoffretail.verifone.source.S3ZipSource;
import com.handsoffretail.verifone.source.ZipSource;

/** The fetch executor and the zip source chosen by {@code hor.storage.mode}. */
@Configuration
class FetchConfig {

    @Bean
    ThreadPoolTaskExecutor fetchExecutor(IngestionProperties.Fetch fetch) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(fetch.workerThreads());
        executor.setMaxPoolSize(fetch.workerThreads());
        executor.setQueueCapacity(fetch.queueCapacity());
        executor.setThreadNamePrefix("fetch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AWAIT_SECONDS);
        return executor;
    }

    @Bean
    ZipSource zipSource(IngestionProperties.Storage storage, IngestionProperties.S3 s3, IngestionProperties.Aws aws) {
        if (storage.mode() == IngestionProperties.StorageMode.LOCAL) {
            return new LocalFolderZipSource(storage.localRoot());
        }
        if (!aws.isComplete()) {
            throw new IllegalStateException("hor.storage.mode=s3 needs hor.aws.access-key-id and hor.aws.secret-access-key "
                    + "in the secrets file (or set hor.storage.mode=local)");
        }
        return new S3ZipSource(s3.bucket(), s3.region(), aws.accessKeyId(), aws.secretAccessKey());
    }

    private static final int AWAIT_SECONDS = 60;
}
