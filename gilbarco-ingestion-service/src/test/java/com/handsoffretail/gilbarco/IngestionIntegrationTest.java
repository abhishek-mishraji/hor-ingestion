package com.handsoffretail.gilbarco;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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

import com.handsoffretail.gilbarco.fetch.FetchQueryService;
import com.handsoffretail.gilbarco.fetch.FetchRequest;
import com.handsoffretail.gilbarco.fetch.FetchService;
import com.handsoffretail.gilbarco.stores.StoreSyncService;

/**
 * Base of the ingestion integration tests: the real application (random port) on gilbarco_db_test, a minimal
 * core.stores in the same database as the auth_db stand-in, zips from a local bucket laid out like S3 (the real
 * Wolf days when the test data is present, plus synthetic days the tests add). One shared context and database
 * per test run; each test uses business days of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IngestionIntegrationTest.TestWiring.class)
public abstract class IngestionIntegrationTest {

    protected static final String PJR = "PJR";
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(180);

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
        registry.add("hor.storage.local-root", () -> bucket().root().toString());
    }

    protected static TestBucket bucket() {
        try {
            return TestBucket.shared();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** master.stores as a sync leaves it (idempotent, cheap). */
    @BeforeEach
    void syncStores() {
        storeSync.sync(TestStores.ADMIN_ID);
    }

    /** Packs synthetic PJR files as Wolf's v{@code version} zip of {@code day} in the shared bucket. */
    protected static void putPjrDay(LocalDate day, int version, List<TestBucket.Entry> entries) {
        try {
            bucket().put(day, version, PJR, entries, TestBucket.Corruption.NONE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Starts a PJR fetch of Wolf through the real flow and waits for its end state. */
    protected FetchRequest fetch(LocalDate day, FetchRequest.Mode mode) {
        return awaitEnd(fetches.start(TestStores.WOLF, day, mode, PJR, TestStores.ADMIN_ID));
    }

    protected FetchRequest awaitEnd(long fetchId) {
        Instant deadline = Instant.now().plus(FETCH_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            FetchRequest request = fetchQueries.detail(fetchId).request();
            if (!request.status().isOpen()) {
                return request;
            }
            pause();
        }
        throw new AssertionError("fetch " + fetchId + " still open after " + FETCH_TIMEOUT);
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
                reset(flyway, jdbc);
                TestStores.reset(jdbc);
            };
        }

        static void reset(Flyway flyway, JdbcTemplate jdbc) {
            jdbc.execute("DROP SCHEMA IF EXISTS report, mcm, pjr, ingest, master, core CASCADE");
            flyway.clean();
            flyway.migrate();
        }
    }
}
