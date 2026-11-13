package com.handsoffretail.verifone.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Two pools: verifone_db (primary: Flyway, transactions, every repository) and a small read-only pool to
 * auth_db, used only by the store sync ({@code @Qualifier(AUTH)}).
 */
@Configuration
public class DataSourceConfig {

    public static final String AUTH = "authJdbc";

    private static final int AUTH_POOL_SIZE = 2;

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties verifoneDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource verifoneDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(defaultCandidate = false)
    @Qualifier(AUTH)
    HikariDataSource authDataSource(IngestionProperties.AuthDb auth) {
        HikariDataSource dataSource = DataSourceBuilder.create().type(HikariDataSource.class)
                .url(auth.url()).username(auth.username()).password(auth.password()).build();
        dataSource.setPoolName("auth-db");
        dataSource.setMaximumPoolSize(AUTH_POOL_SIZE);
        dataSource.setReadOnly(true);
        return dataSource;
    }

    @Bean(defaultCandidate = false)
    @Qualifier(AUTH)
    JdbcClient authJdbc(@Qualifier(AUTH) DataSource authDataSource) {
        return JdbcClient.create(authDataSource);
    }
}
