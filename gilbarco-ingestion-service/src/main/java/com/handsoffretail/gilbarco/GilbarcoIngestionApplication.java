package com.handsoffretail.gilbarco;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Fetches one business day of a Gilbarco Passport store's files of one type (PJR today) from S3 into gilbarco_db,
 * with manifest and load verification. File types plug in through {@code filetypes.FileTypeProcessor}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class GilbarcoIngestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(GilbarcoIngestionApplication.class, args);
    }
}
