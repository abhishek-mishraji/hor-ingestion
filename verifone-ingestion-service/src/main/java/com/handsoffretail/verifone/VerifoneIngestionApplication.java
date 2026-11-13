package com.handsoffretail.verifone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Fetches one business day of a Verifone store's CPJ files from S3 into verifone_db, with load verification. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class VerifoneIngestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(VerifoneIngestionApplication.class, args);
    }
}
