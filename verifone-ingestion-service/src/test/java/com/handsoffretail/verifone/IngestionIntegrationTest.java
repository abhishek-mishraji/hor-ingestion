package com.handsoffretail.verifone;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.handsoffretail.verifone.fetch.FetchQueryService;
import com.handsoffretail.verifone.fetch.FetchRequest;
import com.handsoffretail.verifone.fetch.FetchService;
import com.handsoffretail.verifone.stores.StoreSyncService;

/**
 * Base of the ingestion integration tests: the real application (random port) on verifone_db_test, a minimal
 * core.stores in the same database as the auth_db stand-in, zips from a local bucket built from the real CPJ files.
 * One shared context and database per test run; tests reset what they need.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IngestionIntegrationTest.TestWiring.class)
public abstract class IngestionIntegrationTest {

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(120);

    @Autowired
    protected FetchService fetches;

    @Autowired
    protected FetchQueryService fetchQueries;

    @Autowired
    protected StoreSyncService storeSync;

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void bucket(DynamicPropertyRegistry registry) {
        registry.add("hor.storage.local-root", IngestionIntegrationTest::sharedBucketRoot);
    }

    private static String sharedBucketRoot() {
        try {
            return TestBucket.shared().root().toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** master.stores as a sync leaves it (idempotent, cheap). */
    @BeforeEach
    void syncStores() {
        storeSync.sync(TestStores.ADMIN_ID);
    }

    /** Starts a fetch through the real flow and waits for its end state. */
    protected FetchRequest fetch(long storeId, LocalDate day, FetchRequest.Mode mode) {
        long id = fetches.start(storeId, day, mode, TestStores.ADMIN_ID);
        return awaitEnd(id);
    }

    protected FetchRequest awaitEnd(long fetchId) {
        Instant deadline = Instant.now().plus(FETCH_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            FetchRequest request = fetchQueries.detail(fetchId).request();
            if (request.status() != FetchRequest.Status.RUNNING) {
                return request;
            }
            pause();
        }
        throw new AssertionError("fetch " + fetchId + " still RUNNING after " + FETCH_TIMEOUT);
    }

    protected long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        assertThat(n).isNotNull();
        return n;
    }

    private static void pause() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Clean test database at context start; a decoder for {@link TestTokens} instead of the hor-auth JWKS. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestWiring {

        @Bean
        JwtDecoder testJwtDecoder() {
            return TestTokens.decoder();
        }

        @Bean
        FlywayMigrationStrategy resetDatabase() {
            return flyway -> {
                JdbcTemplate jdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
                resetVerifone(flyway, jdbc);
                TestStores.reset(jdbc);
            };
        }

        static void resetVerifone(Flyway flyway, JdbcTemplate jdbc) {
            jdbc.execute("DROP SCHEMA IF EXISTS report, cpj, ingest, master, core CASCADE");
            flyway.clean();
            flyway.migrate();
        }
    }
}
