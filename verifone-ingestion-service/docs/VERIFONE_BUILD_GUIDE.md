# verifone-ingestion-service: build guide (hand-off to an implementing session)

Read [VERIFONE_SERVICE_PLAN.md](VERIFONE_SERVICE_PLAN.md) first (the *why*). This file is the *how*: run the steps in order.
Every new or changed file is given in full. Everything else is copied unchanged from the old service.

## Rules for the implementer

1. **Do not rewrite proven code.** Only the files listed in Steps 3–6 change. Parser, verifier, loader and load checks stay byte-for-byte except the package rename.
2. **Do not touch** `hor-auth`, `hor-store-automation`, or the old 5442 Postgres cluster. Read from them only.
3. **Do not run superuser SQL.** That is the owner's manual step M2 (§ "Owner's manual steps" at the end).
4. Use Git Bash for the shell scripts. On Windows, run Gradle with `./gradlew.bat`.
5. After each step, run its **Check**. Do not continue on red. If something here is wrong, fix the smallest thing and write it down in `docs/BUILD_REPORT.md`.
6. Keep the code style of the copied code: records, constructor injection, no Lombok, `@Transactional` only in services, Javadoc on public types, 4-space indent, 120 columns.

## Layout and paths used below

`stores-ingestion/` holds independent apps, each opened in its own Claude session. **This session works only inside
`verifone-ingestion-service/`.** It is a standalone Gradle build: there is no root build, and there are no shared files.

```
stores-ingestion/
├── docs/                         history only (superseded IMPLEMENTATION_PLAN.md)
├── verifone-ingestion-service/   THIS app: CLAUDE.md, docs/, infra/, Gradle build, src/
├── ingestion-console/            web UI (separate session, later)
└── gilbarco-ingestion-service/   later
```

```bash
OLD=/c/Users/abhia/OneDrive/HOR-Software/hor-store-automation/services/verifone-ingestion-service
AUTH=/c/Users/abhia/OneDrive/HOR-Software/hor-stores/hor-auth
SVC=/c/Users/abhia/OneDrive/HOR-Software/hor-stores/stores-ingestion/verifone-ingestion-service
J=$SVC/src/main/java/com/handsoffretail/verifone
T=$SVC/src/test/java/com/handsoffretail/verifone
```

**Already in place (created 2026-10-22, do not recreate):** `gradlew`, `gradlew.bat`, `gradle/wrapper/` (Gradle 9.8.1),
`settings.gradle.kts`, `gradle.properties`, `build.gradle.kts`, `.gitignore`, `.gitattributes`,
`infra/postgres/01_verifone_databases.sql`. Their contents are shown in §1.2 for reference only.

## Development database model (owner decision, 2026-10-22)

Development uses **one** PostgreSQL login for every HOR database: `auth_svc`, already created by
`hor-auth/infra/postgres/01_auth_database.sql`.

**Settings and secrets live in `verifone-ingestion-service/.env`** (owner decision, 2026-10-22). It is already created
with the real values and is git-ignored. `.env.example` documents every key. Spring imports it with
`spring.config.import=optional:file:.env[.properties]`, and real environment variables of the same name override it
(production). Never print, log or commit `.env`. Keys: `HOR_DB_HOST`, `HOR_DB_PORT`, `HOR_DB_USERNAME`,
`HOR_DB_PASSWORD`, `HOR_AWS_ACCESS_KEY_ID`, `HOR_AWS_SECRET_ACCESS_KEY`, `HOR_STORAGE_MODE`, `HOR_TESTDATA_ROOT`.
`.env` is parsed as a Java properties file, so paths use forward slashes.

- `auth_svc` owns `auth_db`, `auth_db_test`, `verifone_db` and `verifone_db_test`.
- Because it owns `auth_db`, it can read `core.stores` without any grant script.
- Production will split this into `verifone_ingest_svc` with a SELECT-only grant. That is a config change only: the
  `hor.verifone.*` properties carry the user name and password.

---

## Step 1: Scaffold, copy, rename

### 1.1 Copy script (run once from Git Bash)

```bash
set -euo pipefail
OLD=/c/Users/abhia/OneDrive/HOR-Software/hor-store-automation/services/verifone-ingestion-service
AUTH=/c/Users/abhia/OneDrive/HOR-Software/hor-stores/hor-auth
SVC=/c/Users/abhia/OneDrive/HOR-Software/hor-stores/stores-ingestion/verifone-ingestion-service
J=$SVC/src/main/java/com/handsoffretail/verifone
T=$SVC/src/test/java/com/handsoffretail/verifone

mkdir -p "$SVC/src/main/java/com/handsoffretail" "$SVC/src/test/java/com/handsoffretail" \
         "$SVC/src/main/resources/db/migration" "$SVC/src/test/resources"

# Sources only: never bin/, build/, .gradle/, hs_err*, replay*.
cp -r "$OLD/src/main/java/com/handsoff/hor/ingestion/verifone" "$SVC/src/main/java/com/handsoffretail/"
cp -r "$OLD/src/test/java/com/handsoff/hor/ingestion/verifone" "$SVC/src/test/java/com/handsoffretail/"
cp "$OLD/src/main/resources/db/migration/V1__verifone_schema.sql" "$SVC/src/main/resources/db/migration/"

# Package rename + the three shared helpers that become local copies in common/.
find "$SVC/src" -name '*.java' -exec sed -i \
  -e 's/com\.handsoff\.hor\.ingestion\.verifone/com.handsoffretail.verifone/g' \
  -e 's/com\.handsoff\.hor\.shared\.web\.PageResult/com.handsoffretail.verifone.common.PageResult/g' \
  -e 's/com\.handsoff\.hor\.shared\.web\.Paging/com.handsoffretail.verifone.common.Paging/g' \
  -e 's/com\.handsoff\.hor\.shared\.db\.PostgresErrors/com.handsoffretail.verifone.common.PostgresErrors/g' \
  -e 's/\bTestPlatform\b/TestStores/g' \
  {} +

# Javadoc reference in FetchRepository.
sed -i 's/com\.handsoff\.hor\.shared\.error\.ConflictException when/com.handsoffretail.auth.client.DomainException (FETCH_RUNNING) when/' \
  "$J/fetch/FetchRepository.java"

# TestData and S3ZipSourceContractTest read ~/.hor-store-automation; Step 5.9 switches them to .env (TestEnv).

# Replaced by new files in Steps 3–6.
rm "$J/stores/PlatformStoreDirectory.java" "$T/TestJwt.java" "$T/TestPlatform.java"

echo "Files still importing the old shared library (all are rewritten in Steps 3-6):"
grep -rl 'com\.handsoff\.hor\.shared' "$SVC/src" || true
```

The expected list printed at the end has exactly these 13 files: `config/SecurityConfig`, `days/DayController`,
`days/DayService`, `departments/DepartmentController`, `fetch/FetchController`, `fetch/FetchQueryService`,
`fetch/FetchService`, `fetch/FetchWorker`, `fetch/JdbcFetchRepository`, `stores/JdbcMasterStores`,
`stores/StoreDirectory`, `test/.../fetch/FetchBehaviourTest`, `test/.../IngestionIntegrationTest`.

### 1.2 Build files (already in place, reference only)

`$SVC/.gitignore`
```gitignore
build/
bin/
.gradle/
out/
node_modules/
dist/
*.log
hs_err_pid*
replay_pid*
*.zip
testdata/
secrets*.properties
*.pem
.idea/
.vscode/

.env
!.env.example
```

`$SVC/.gitattributes`
```gitattributes
* text=auto eol=lf
*.bat text eol=crlf
*.jar binary
```

### 1.3 Service build files (already in place, reference only)

`$SVC/settings.gradle.kts`
```kotlin
pluginManagement {
    // Plugin versions live in gradle.properties.
    plugins {
        id("org.springframework.boot") version providers.gradleProperty("springBootVersion").get()
        id("io.spring.dependency-management") version providers.gradleProperty("dependencyManagementVersion").get()
    }
}

rootProject.name = "verifone-ingestion-service"
```

`$SVC/gradle.properties`
```properties
org.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8
org.gradle.caching=true

springBootVersion=4.1.1
dependencyManagementVersion=1.1.7
awsSdkVersion=2.55.3
authClientVersion=0.1.0
```

`$SVC/build.gradle.kts`
```kotlin
plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "com.handsoffretail"
version = "0.1.0"
description = "verifone-ingestion-service: admin fetch of one business day from S3 into verifone_db"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    // auth-client is published by hor-auth with publishToMavenLocal.
    mavenLocal()
    mavenCentral()
}

val awsSdkVersion = providers.gradleProperty("awsSdkVersion").get()
val authClientVersion = providers.gradleProperty("authClientVersion").get()

dependencyManagement {
    imports {
        mavenBom("software.amazon.awssdk:bom:$awsSdkVersion")
    }
}

dependencies {
    implementation("com.handsoffretail:auth-client:$authClientVersion")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.nimbusds:nimbus-jose-jwt")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Integration tests share verifone_db_test; one test JVM at a time.
    maxParallelForks = 1
    // The golden test loads 20 real days.
    maxHeapSize = "1g"
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.bootJar {
    archiveFileName = "verifone-ingestion-service.jar"
}

tasks.jar {
    enabled = false
}
```

### 1.4 Database script (already in place; the owner runs it, see M2)

`$SVC/infra/postgres/01_verifone_databases.sql`
```sql
-- Development: run ONCE as superuser (postgres) on the PostgreSQL 17 that holds auth_db.
-- One login role for every HOR database in development: auth_svc (created by
-- hor-auth/infra/postgres/01_auth_database.sql). Flyway in verifone-ingestion-service creates
-- every schema and table; this script only creates the empty databases.
--   psql -U postgres -f infra/postgres/01_verifone_databases.sql
CREATE DATABASE verifone_db OWNER auth_svc ENCODING 'UTF8' TEMPLATE template0;
REVOKE ALL ON DATABASE verifone_db FROM PUBLIC;
ALTER DATABASE verifone_db SET timezone TO 'UTC';

CREATE DATABASE verifone_db_test OWNER auth_svc ENCODING 'UTF8' TEMPLATE template0;
REVOKE ALL ON DATABASE verifone_db_test FROM PUBLIC;
ALTER DATABASE verifone_db_test SET timezone TO 'UTC';
```

**Check 1:** `cd $SVC && ./gradlew.bat help` succeeds. Compilation is still red until Step 4.

---

## Step 2: Configuration

`$SVC/src/main/resources/application.properties` (replaces the copied file if one was copied; Step 1 did not copy it)
```properties
# verifone-ingestion-service: admin fetch of one business day from S3 -> verifone_db.
# Settings and secrets come from .env in the working directory (this service's folder), never from this file.
# Real environment variables of the same name win over .env (production).
spring.application.name=verifone-ingestion-service
spring.config.import=optional:file:.env[.properties]

# logServerErrorDetail=false keeps row values (possibly personal data) out of exception messages.
spring.datasource.url=jdbc:postgresql://${hor.verifone.db.host}:${hor.verifone.db.port}/verifone_db?reWriteBatchedInserts=true&logServerErrorDetail=false
spring.datasource.username=${hor.verifone.db.username}
spring.datasource.password=${hor.verifone.db.password}
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.pool-name=verifone-db

spring.flyway.enabled=true
spring.flyway.clean-disabled=true
spring.flyway.validate-on-migrate=true

spring.jackson.default-property-inclusion=non_null

server.port=8092
server.address=127.0.0.1
server.shutdown=graceful

# Development: one login (auth_svc) for every HOR database (VERIFONE_BUILD_GUIDE.md).
hor.verifone.db.host=${HOR_DB_HOST:localhost}
hor.verifone.db.port=${HOR_DB_PORT:5432}
hor.verifone.db.username=${HOR_DB_USERNAME:auth_svc}
hor.verifone.db.password=${HOR_DB_PASSWORD}

# Empty when not set: hor.storage.mode=s3 then fails at start-up with a clear message (FetchConfig).
hor.aws.access-key-id=${HOR_AWS_ACCESS_KEY_ID:}
hor.aws.secret-access-key=${HOR_AWS_SECRET_ACCESS_KEY:}

# Read-only pool to auth_db; used only by POST /stores/sync.
hor.verifone.auth-db.url=jdbc:postgresql://${hor.verifone.db.host}:${hor.verifone.db.port}/auth_db?logServerErrorDetail=false
hor.verifone.auth-db.username=${hor.verifone.db.username}
hor.verifone.auth-db.password=${hor.verifone.db.password}

hor.auth.client.jwks-uri=http://127.0.0.1:8091/api/auth/.well-known/jwks.json
hor.auth.client.allowed-origins=http://localhost:5182,http://127.0.0.1:5182

hor.s3.bucket=hor-stores-data
hor.s3.region=us-east-1

# s3 = the real bucket (needs the HOR_AWS_* keys); local = a folder laid out like the bucket
hor.storage.mode=${HOR_STORAGE_MODE:s3}
hor.storage.local-root=${user.home}/.hor-stores/local-bucket

hor.fetch.stale-after=PT30M
hor.fetch.worker-threads=2
hor.fetch.queue-capacity=20
hor.fetch.max-uncompressed-bytes=524288000
```

`$SVC/src/test/resources/application-test.properties` (overwrite the copied one)
```properties
spring.datasource.url=jdbc:postgresql://${hor.verifone.db.host}:${hor.verifone.db.port}/verifone_db_test?reWriteBatchedInserts=true&logServerErrorDetail=false
spring.flyway.clean-disabled=false

# Tests keep a minimal core.stores inside verifone_db_test itself (TestStores), so they do not depend on auth_db_test.
hor.verifone.auth-db.url=jdbc:postgresql://${hor.verifone.db.host}:${hor.verifone.db.port}/verifone_db_test?logServerErrorDetail=false

hor.storage.mode=local
hor.fetch.worker-threads=2
```

---

## Step 3: New code

### 3.1 `$J/common/VerifoneErrorCode.java`
```java
package com.handsoffretail.verifone.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import com.handsoffretail.auth.client.CommonErrorCode;
import com.handsoffretail.auth.client.DomainException;
import com.handsoffretail.auth.client.ErrorCode;

/**
 * The error codes of this service. Rendered as RFC 9457 problem details by auth-client's ProblemDetailsAdvice
 * ({@code code} property = the enum name). Codes are kept from the old service so clients do not change.
 */
public enum VerifoneErrorCode implements ErrorCode {

    FETCH_RUNNING(HttpStatus.CONFLICT),
    FETCH_QUEUE_FULL(HttpStatus.CONFLICT),
    STORE_INACTIVE(HttpStatus.CONFLICT),
    STORE_CODE_MISMATCH(HttpStatus.CONFLICT),
    STORE_NOT_VERIFONE(HttpStatus.UNPROCESSABLE_CONTENT),
    DATE_IN_FUTURE(HttpStatus.UNPROCESSABLE_CONTENT),
    BAD_RANGE(HttpStatus.UNPROCESSABLE_CONTENT),
    BAD_LIMIT(HttpStatus.UNPROCESSABLE_CONTENT),
    BAD_OFFSET(HttpStatus.UNPROCESSABLE_CONTENT);

    private final HttpStatusCode status;

    VerifoneErrorCode(HttpStatusCode status) {
        this.status = status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatusCode status() {
        return status;
    }

    /** The exception for this code. */
    public DomainException exception(String detail) {
        return new DomainException(this, detail);
    }

    /** 404 for a thing that does not exist (or that the caller may not know exists). */
    public static DomainException notFound(String what, long id) {
        return new DomainException(CommonErrorCode.NOT_FOUND, what + " " + id + " not found");
    }
}
```

> If `HttpStatus.UNPROCESSABLE_CONTENT` does not resolve in Spring 7, use `HttpStatus.UNPROCESSABLE_ENTITY`.

### 3.2 `$J/common/PageResult.java`
```java
package com.handsoffretail.verifone.common;

import java.util.List;
import java.util.function.Function;

/** One page of a list, with the total number of rows that match. */
public record PageResult<T>(List<T> items, long total, int limit, long offset) {

    public PageResult {
        items = List.copyOf(items);
    }

    public static <T> PageResult<T> of(List<T> items, long total, Paging paging) {
        return new PageResult<>(items, total, paging.limit(), paging.offset());
    }

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(items.stream().map(mapper).toList(), total, limit, offset);
    }
}
```

### 3.3 `$J/common/Paging.java`
```java
package com.handsoffretail.verifone.common;

/** A {@code limit} / {@code offset} page request (limit 1..500, default 50). */
public record Paging(int limit, long offset) {

    public static final int MAX_LIMIT = 500;
    public static final int DEFAULT_LIMIT = 50;

    public Paging {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw VerifoneErrorCode.BAD_LIMIT.exception("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw VerifoneErrorCode.BAD_OFFSET.exception("offset must not be negative");
        }
    }

    /** From optional query parameters; missing values take the defaults. */
    public static Paging of(Integer limit, Long offset) {
        return new Paging(limit == null ? DEFAULT_LIMIT : limit, offset == null ? 0 : offset);
    }
}
```

### 3.4 `$J/common/PostgresErrors.java`
```java
package com.handsoffretail.verifone.common;

import java.util.Optional;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Reads what PostgreSQL said about a failed statement, so repositories can turn a constraint
 * violation into a domain exception with a clear message.
 */
public final class PostgresErrors {

    public static final String UNIQUE_VIOLATION = "23505";
    public static final String CHECK_VIOLATION = "23514";
    public static final String FOREIGN_KEY_VIOLATION = "23503";

    private PostgresErrors() {
    }

    /** The server error under {@code e}'s cause chain, if any. */
    public static Optional<ServerErrorMessage> serverError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return Optional.of(psql.getServerErrorMessage());
            }
        }
        return Optional.empty();
    }

    public static Optional<String> constraint(Throwable e) {
        return serverError(e).map(ServerErrorMessage::getConstraint);
    }

    public static boolean isViolation(Throwable e, String sqlState) {
        return serverError(e).map(message -> sqlState.equals(message.getSQLState())).orElse(false);
    }

    /** The message a trigger raised, or the server's own message. */
    public static Optional<String> message(Throwable e) {
        return serverError(e).map(ServerErrorMessage::getMessage);
    }
}
```

### 3.5 `$SVC/src/main/resources/db/migration/V2__store_replica.sql`
```sql
-- =====================================================================================
-- master.stores becomes a full replica of the VERIFONE rows of auth_db core.stores,
-- refreshed only by POST /api/ingestion/verifone/stores/sync (owner, 2026-10-22).
-- store_id = auth_db core.stores.store_id (V1 comments that name platform_db are historical).
-- Fetches read only this table; auth_db is read only during a sync.
-- A store that is no longer VERIFONE (or is gone) in auth_db becomes INACTIVE here; rows are
-- never deleted (cpj / ingest rows reference them).
-- Valid as NOT NULL without defaults: the table is empty when this runs on a new database.
-- =====================================================================================
ALTER TABLE master.stores
    ADD COLUMN store_name text        NOT NULL,
    ADD COLUMN s3_folder  text        NOT NULL,
    ADD COLUMN timezone   text        NOT NULL,
    ADD COLUMN status     text        NOT NULL,
    ADD COLUMN synced_at  timestamptz NOT NULL,
    ADD CONSTRAINT uq_master_stores_s3     UNIQUE (s3_folder),
    ADD CONSTRAINT ck_master_stores_name   CHECK (length(btrim(store_name)) BETWEEN 1 AND 200),
    ADD CONSTRAINT ck_master_stores_s3     CHECK (s3_folder ~ '^[^/].*/$'),
    ADD CONSTRAINT ck_master_stores_status CHECK (status IN ('ACTIVE', 'INACTIVE'));
```

### 3.6 `$J/config/IngestionProperties.java` (full replacement: `PlatformDb` → `AuthDb`)
```java
package com.handsoffretail.verifone.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The settings of verifone-ingestion-service, as records (one per prefix). */
public final class IngestionProperties {

    private IngestionProperties() {
    }

    /** Where zips come from: {@code s3} (production) or {@code local} (a folder laid out like the bucket). */
    public enum StorageMode {
        S3,
        LOCAL
    }

    /** {@code hor.storage.*}. */
    @ConfigurationProperties("hor.storage")
    public record Storage(@DefaultValue("S3") StorageMode mode, Path localRoot, Path workDir) {

        public Storage {
            Objects.requireNonNull(mode, "hor.storage.mode");
            if (mode == StorageMode.LOCAL && localRoot == null) {
                throw new IllegalArgumentException("hor.storage.local-root is required when hor.storage.mode=local");
            }
            workDir = workDir == null ? Path.of(System.getProperty("java.io.tmpdir"), "hor-verifone-ingestion") : workDir;
        }
    }

    /** {@code hor.s3.*}. */
    @ConfigurationProperties("hor.s3")
    public record S3(@DefaultValue("hor-stores-data") String bucket, @DefaultValue("us-east-1") String region) {
    }

    /** {@code hor.aws.*}: the read-only key from the secrets file. Never printed. */
    @ConfigurationProperties("hor.aws")
    public record Aws(String accessKeyId, String secretAccessKey) {

        public boolean isComplete() {
            return accessKeyId != null && !accessKeyId.isBlank() && secretAccessKey != null && !secretAccessKey.isBlank();
        }

        @Override
        public String toString() {
            return "Aws[key " + (isComplete() ? "set" : "not set") + "]";
        }
    }

    /** {@code hor.verifone.auth-db.*}: read-only connection to auth_db (core.stores), used by the store sync. */
    @ConfigurationProperties("hor.verifone.auth-db")
    public record AuthDb(String url, String username, String password) {

        public AuthDb {
            Objects.requireNonNull(url, "hor.verifone.auth-db.url");
            Objects.requireNonNull(username, "hor.verifone.auth-db.username");
        }

        @Override
        public String toString() {
            return "AuthDb[" + url + ", " + username + "]";
        }
    }

    /** {@code hor.fetch.*}. */
    @ConfigurationProperties("hor.fetch")
    public record Fetch(
            @DefaultValue("PT30M") Duration staleAfter,
            @DefaultValue("2") int workerThreads,
            @DefaultValue("20") int queueCapacity,
            @DefaultValue("524288000") long maxUncompressedBytes) {

        public Fetch {
            if (workerThreads < 1 || queueCapacity < 1 || maxUncompressedBytes < 1) {
                throw new IllegalArgumentException("hor.fetch worker-threads, queue-capacity and max-uncompressed-bytes must be positive");
            }
        }
    }
}
```

### 3.7 `$J/config/DataSourceConfig.java` (full replacement)
```java
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
```

### 3.8 `$J/config/SecurityConfig.java` (full replacement)
```java
package com.handsoffretail.verifone.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import com.handsoffretail.auth.client.CsrfHeaderFilter;
import com.handsoffretail.auth.client.HorHttpSecurity;
import com.handsoffretail.auth.client.ProblemResponses;
import com.handsoffretail.auth.client.RequestLoggingFilter;

/**
 * The hor-auth resource-server chain (cookie {@code hor_access}, {@code X-HOR-Request} + Origin on writes,
 * problem-detail errors) with this service's rule: every endpoint is PLATFORM_ADMIN only (owner, 2026-10-22).
 * Authorities are the bare role claim (no ROLE_ prefix), hence {@code hasAuthority}.
 * The JwtDecoder comes from auth-client (JWKS of hor-auth); tests replace it with a bean of their own.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    @Bean
    SecurityFilterChain ingestionFilterChain(HttpSecurity http, BearerTokenResolver tokenResolver,
            CsrfHeaderFilter csrfHeaderFilter, RequestLoggingFilter requestLoggingFilter) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/ingestion/verifone/**").hasAuthority(PLATFORM_ADMIN)
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(ProblemResponses.authenticationEntryPoint())
                        .bearerTokenResolver(tokenResolver)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(HorHttpSecurity.jwtAuthenticationConverter())))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(ProblemResponses.authenticationEntryPoint())
                        .accessDeniedHandler(ProblemResponses.accessDeniedHandler()))
                .addFilterAfter(requestLoggingFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(csrfHeaderFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
```

### 3.9 Stores package

Delete nothing more (Step 1 removed `PlatformStoreDirectory`). Replace or create these files.

`$J/stores/StoreRef.java`: **unchanged** (copied). `posVendor` is always `VERIFONE` now; the check in `FetchService` stays as a guard.

`$J/stores/StoreDirectory.java` (full replacement)
```java
package com.handsoffretail.verifone.stores;

import java.util.Optional;

import com.handsoffretail.auth.client.HorPrincipal;
import com.handsoffretail.auth.client.StoreAccess;
import com.handsoffretail.verifone.common.VerifoneErrorCode;

/** The Verifone stores this service knows: the master.stores replica (filled by the store sync). */
public interface StoreDirectory {

    Optional<StoreRef> find(long storeId);

    /**
     * The store, if it exists and the caller may see it; otherwise 404 (never 403, so ids cannot be probed).
     */
    default StoreRef require(HorPrincipal caller, long storeId) {
        StoreAccess.requireStore(caller, storeId);
        return find(storeId).orElseThrow(() -> VerifoneErrorCode.notFound("store", storeId));
    }
}
```

`$J/stores/MasterStore.java` (new)
```java
package com.handsoffretail.verifone.stores;

import java.time.Instant;
import java.util.Objects;

/** One master.stores row. {@code active} = status ACTIVE. */
public record MasterStore(long storeId, String storeCode, String storeName, String s3Folder, String timezone,
        boolean active, Instant syncedAt) {

    public MasterStore {
        Objects.requireNonNull(storeCode, "storeCode");
        Objects.requireNonNull(storeName, "storeName");
        Objects.requireNonNull(s3Folder, "s3Folder");
        Objects.requireNonNull(timezone, "timezone");
        Objects.requireNonNull(syncedAt, "syncedAt");
    }

    /** True when the replica already holds what auth_db says (the code is compared by the caller). */
    boolean sameAs(AuthStore source) {
        return storeName.equals(source.storeName()) && s3Folder.equals(source.s3Folder())
                && timezone.equals(source.timezone()) && active == source.active();
    }
}
```

`$J/stores/AuthStore.java` (new)
```java
package com.handsoffretail.verifone.stores;

import java.util.Objects;

/** A VERIFONE row of auth_db core.stores, as the sync reads it. */
public record AuthStore(long storeId, String storeCode, String storeName, String s3Folder, String timezone,
        boolean active) {

    public AuthStore {
        Objects.requireNonNull(storeCode, "storeCode");
        Objects.requireNonNull(storeName, "storeName");
        Objects.requireNonNull(s3Folder, "s3Folder");
        Objects.requireNonNull(timezone, "timezone");
    }
}
```

`$J/stores/AuthStores.java` (new)
```java
package com.handsoffretail.verifone.stores;

import java.util.List;

/** Read side of auth_db core.stores (the master of store data). */
public interface AuthStores {

    /** Every store whose pos_vendor is VERIFONE, active or not, ordered by id. */
    List<AuthStore> verifoneStores();
}
```

`$J/stores/JdbcAuthStores.java` (new)
```java
package com.handsoffretail.verifone.stores;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.handsoffretail.verifone.config.DataSourceConfig;

@Repository
class JdbcAuthStores implements AuthStores {

    private final JdbcClient auth;

    JdbcAuthStores(@Qualifier(DataSourceConfig.AUTH) JdbcClient auth) {
        this.auth = auth;
    }

    @Override
    public List<AuthStore> verifoneStores() {
        return auth.sql("""
                SELECT store_id, store_code, store_name, s3_folder, timezone, status
                FROM core.stores WHERE pos_vendor = 'VERIFONE' ORDER BY store_id
                """)
                .query((rs, row) -> new AuthStore(rs.getLong("store_id"), rs.getString("store_code"),
                        rs.getString("store_name"), rs.getString("s3_folder"), rs.getString("timezone"),
                        "ACTIVE".equals(rs.getString("status"))))
                .list();
    }
}
```

`$J/stores/MasterStores.java` (full replacement)
```java
package com.handsoffretail.verifone.stores;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Write side of master.stores; only the store sync uses it. */
public interface MasterStores {

    List<MasterStore> all();

    void insert(AuthStore store, Instant now);

    /** Name, folder, timezone and status from auth_db; the code never changes here. */
    void update(AuthStore store, Instant now);

    /** Marks the rows as seen by this sync (no other change). */
    void touch(Collection<Long> storeIds, Instant now);

    void deactivate(long storeId, Instant now);
}
```

`$J/stores/JdbcMasterStores.java` (full replacement; implements both the read port and the write port)
```java
package com.handsoffretail.verifone.stores;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcMasterStores implements MasterStores, StoreDirectory {

    private static final String SELECT = """
            SELECT store_id, store_code, store_name, s3_folder, timezone, status, synced_at FROM master.stores
            """;

    private final JdbcClient jdbc;

    JdbcMasterStores(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoreRef> find(long storeId) {
        return jdbc.sql(SELECT + "WHERE store_id = :id").param("id", storeId).query(JdbcMasterStores::row).optional()
                .map(s -> new StoreRef(s.storeId(), s.storeCode(), s.storeName(), StoreRef.VERIFONE, s.s3Folder(),
                        ZoneId.of(s.timezone()), s.active()));
    }

    @Override
    public List<MasterStore> all() {
        return jdbc.sql(SELECT + "ORDER BY store_code").query(JdbcMasterStores::row).list();
    }

    @Override
    public void insert(AuthStore s, Instant now) {
        jdbc.sql("""
                INSERT INTO master.stores (store_id, store_code, store_name, s3_folder, timezone, status, synced_at)
                VALUES (:id, :code, :name, :folder, :tz, :status, :now)
                """)
                .param("id", s.storeId()).param("code", s.storeCode()).param("name", s.storeName())
                .param("folder", s.s3Folder()).param("tz", s.timezone()).param("status", status(s.active()))
                .param("now", utc(now))
                .update();
    }

    @Override
    public void update(AuthStore s, Instant now) {
        jdbc.sql("""
                UPDATE master.stores SET store_name = :name, s3_folder = :folder, timezone = :tz, status = :status,
                       synced_at = :now
                WHERE store_id = :id
                """)
                .param("id", s.storeId()).param("name", s.storeName()).param("folder", s.s3Folder())
                .param("tz", s.timezone()).param("status", status(s.active())).param("now", utc(now))
                .update();
    }

    @Override
    public void touch(Collection<Long> storeIds, Instant now) {
        if (storeIds.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE master.stores SET synced_at = :now WHERE store_id IN (:ids)")
                .param("now", utc(now)).param("ids", List.copyOf(storeIds))
                .update();
    }

    @Override
    public void deactivate(long storeId, Instant now) {
        jdbc.sql("UPDATE master.stores SET status = 'INACTIVE', synced_at = :now WHERE store_id = :id")
                .param("id", storeId).param("now", utc(now))
                .update();
    }

    private static MasterStore row(ResultSet rs, int row) throws SQLException {
        return new MasterStore(rs.getLong("store_id"), rs.getString("store_code"), rs.getString("store_name"),
                rs.getString("s3_folder"), rs.getString("timezone"), "ACTIVE".equals(rs.getString("status")),
                rs.getObject("synced_at", OffsetDateTime.class).toInstant());
    }

    private static String status(boolean active) {
        return active ? "ACTIVE" : "INACTIVE";
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
```

`$J/stores/StoreSyncService.java` (new)
```java
package com.handsoffretail.verifone.stores;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: refresh master.stores from the VERIFONE rows of auth_db core.stores (VERIFONE_SERVICE_PLAN.md §6.2).
 * One transaction on verifone_db; auth_db is only read. Rules:
 * new id → insert; same id and code → update when something changed; same id, other code → conflict, untouched
 * (codes are baked into S3 keys and loaded data); code owned by another id → conflict, skipped;
 * no longer VERIFONE or gone → INACTIVE (never deleted).
 */
@Service
public class StoreSyncService {

    private static final Logger log = LoggerFactory.getLogger(StoreSyncService.class);

    private final AuthStores authStores;
    private final MasterStores masterStores;
    private final Clock clock;

    public StoreSyncService(AuthStores authStores, MasterStores masterStores, Clock clock) {
        this.authStores = authStores;
        this.masterStores = masterStores;
        this.clock = clock;
    }

    @Transactional
    public Result sync(long actorId) {
        Instant now = clock.instant();
        Map<Long, MasterStore> current = masterStores.all().stream()
                .collect(Collectors.toMap(MasterStore::storeId, Function.identity()));
        Map<String, Long> idByCode = current.values().stream()
                .collect(Collectors.toMap(MasterStore::storeCode, MasterStore::storeId));
        Set<Long> seen = new HashSet<>();
        List<Long> unchangedIds = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        int inserted = 0;
        int updated = 0;
        for (AuthStore source : authStores.verifoneStores()) {
            MasterStore existing = current.get(source.storeId());
            if (existing == null) {
                Long owner = idByCode.get(source.storeCode());
                if (owner != null) {
                    conflicts.add(new Conflict(source.storeId(), source.storeCode(),
                            "store code already used by store " + owner + " in verifone_db"));
                    continue;
                }
                masterStores.insert(source, now);
                idByCode.put(source.storeCode(), source.storeId());
                inserted++;
            } else if (!existing.storeCode().equals(source.storeCode())) {
                conflicts.add(new Conflict(source.storeId(), source.storeCode(), "STORE_CODE_MISMATCH: verifone_db has "
                        + existing.storeCode() + "; a store code cannot change once data may be loaded"));
            } else if (existing.sameAs(source)) {
                unchangedIds.add(source.storeId());
            } else {
                masterStores.update(source, now);
                updated++;
            }
            seen.add(source.storeId());
        }
        masterStores.touch(unchangedIds, now);
        int deactivated = 0;
        for (MasterStore store : current.values()) {
            if (!seen.contains(store.storeId()) && store.active()) {
                masterStores.deactivate(store.storeId(), now);
                deactivated++;
            }
        }
        Result result = new Result(inserted, updated, deactivated, unchangedIds.size(), conflicts);
        log.info("Store sync by admin {}: {} inserted, {} updated, {} deactivated, {} unchanged, {} conflict(s)",
                actorId, inserted, updated, deactivated, unchangedIds.size(), conflicts.size());
        return result;
    }

    /** What a sync changed. */
    public record Result(int inserted, int updated, int deactivated, int unchanged, List<Conflict> conflicts) {

        public Result {
            conflicts = List.copyOf(conflicts);
        }
    }

    /** A store the sync could not apply. */
    public record Conflict(long storeId, String storeCode, String reason) {
    }
}
```

`$J/stores/StoreController.java` (new)
```java
package com.handsoffretail.verifone.stores;

import java.time.Instant;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.handsoffretail.auth.client.HorPrincipal;

@RestController
@RequestMapping("/api/ingestion/verifone/stores")
class StoreController {

    private final MasterStores stores;
    private final StoreSyncService sync;

    StoreController(MasterStores stores, StoreSyncService sync) {
        this.stores = stores;
        this.sync = sync;
    }

    /** The Verifone stores known to verifone_db, filtered to the caller's stores. */
    @GetMapping
    List<StoreResponse> list(@AuthenticationPrincipal Jwt jwt) {
        HorPrincipal caller = HorPrincipal.from(jwt);
        return stores.all().stream()
                .filter(s -> caller.allStores() || caller.storeIds().contains(s.storeId()))
                .map(StoreResponse::from).toList();
    }

    /** Copies the VERIFONE stores of auth_db into master.stores. */
    @PostMapping("/sync")
    StoreSyncService.Result sync(@AuthenticationPrincipal Jwt jwt) {
        return sync.sync(HorPrincipal.from(jwt).id());
    }

    record StoreResponse(long storeId, String storeCode, String storeName, String s3Folder, String timezone,
            String status, Instant syncedAt) {

        static StoreResponse from(MasterStore s) {
            return new StoreResponse(s.storeId(), s.storeCode(), s.storeName(), s.s3Folder(), s.timezone(),
                    s.active() ? "ACTIVE" : "INACTIVE", s.syncedAt());
        }
    }
}
```

---

## Step 4: Rewired existing files (full replacements)

### 4.1 `$J/days/DayController.java`
```java
package com.handsoffretail.verifone.days;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.handsoffretail.auth.client.HorPrincipal;
import com.handsoffretail.verifone.stores.StoreDirectory;

@RestController
@RequestMapping("/api/ingestion/verifone/stores/{storeId}")
class DayController {

    private static final int FIRST_YEAR = 2000;
    private static final int LAST_YEAR = 2100;

    private final DayService days;
    private final StoreDirectory stores;

    DayController(DayService days, StoreDirectory stores) {
        this.days = days;
        this.stores = stores;
    }

    @GetMapping("/available-days")
    List<LocalDate> available(@AuthenticationPrincipal Jwt jwt, @PathVariable long storeId,
            @RequestParam @Min(FIRST_YEAR) @Max(LAST_YEAR) int year, @RequestParam @Min(1) @Max(12) int month) {
        stores.require(HorPrincipal.from(jwt), storeId);
        return days.availableDays(storeId, YearMonth.of(year, month));
    }

    @GetMapping("/loaded-days")
    List<DayQueries.LoadedDay> loaded(@AuthenticationPrincipal Jwt jwt, @PathVariable long storeId,
            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        stores.require(HorPrincipal.from(jwt), storeId);
        return days.loadedDays(storeId, from, to);
    }
}
```

### 4.2 `$J/days/DayService.java`
```java
package com.handsoffretail.verifone.days;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.handsoffretail.verifone.common.VerifoneErrorCode;
import com.handsoffretail.verifone.source.ZipSource;
import com.handsoffretail.verifone.stores.StoreDirectory;
import com.handsoffretail.verifone.stores.StoreRef;

/** Use cases: the days of a month that have a CPJ zip in S3, and the days already loaded. */
@Service
public class DayService {

    /** The loaded-days calendar covers at most this many days per request. */
    static final int MAX_RANGE_DAYS = 400;

    private final StoreDirectory stores;
    private final ZipSource source;
    private final DayQueries days;

    public DayService(StoreDirectory stores, ZipSource source, DayQueries days) {
        this.stores = stores;
        this.source = source;
        this.days = days;
    }

    public List<LocalDate> availableDays(long storeId, YearMonth month) {
        StoreRef store = stores.find(storeId).orElseThrow(() -> VerifoneErrorCode.notFound("store", storeId));
        return source.availableCpjDays(store.s3Folder(), month);
    }

    @Transactional(readOnly = true)
    public List<DayQueries.LoadedDay> loadedDays(long storeId, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw VerifoneErrorCode.BAD_RANGE.exception("from must be before to, at most " + MAX_RANGE_DAYS + " days apart");
        }
        return days.loadedDays(storeId, from, to);
    }
}
```

### 4.3 `$J/departments/DepartmentController.java`
```java
package com.handsoffretail.verifone.departments;

import java.time.LocalDate;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.handsoffretail.auth.client.HorPrincipal;
import com.handsoffretail.verifone.stores.StoreDirectory;

@RestController
@RequestMapping("/api/ingestion/verifone/stores/{storeId}/departments")
class DepartmentController {

    private final DepartmentService departments;
    private final StoreDirectory stores;

    DepartmentController(DepartmentService departments, StoreDirectory stores) {
        this.departments = departments;
        this.stores = stores;
    }

    @GetMapping
    List<DepartmentResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long storeId) {
        stores.require(HorPrincipal.from(jwt), storeId);
        return departments.list(storeId).stream().map(DepartmentResponse::from).toList();
    }

    record NameResponse(String name, String deptType, LocalDate firstSeen, LocalDate lastSeen) {
    }

    record DepartmentResponse(int deptNumber, String currentName, List<NameResponse> names,
            Department.LotteryClass lotteryClass) {

        static DepartmentResponse from(Department d) {
            return new DepartmentResponse(d.deptNumber(), d.currentName(), d.names().stream()
                    .map(n -> new NameResponse(n.name(), n.deptType(), n.firstSeen().orElse(null), n.lastSeen().orElse(null)))
                    .toList(), d.lotteryClass().orElse(null));
        }
    }
}
```

### 4.4 `$J/fetch/FetchController.java`
```java
package com.handsoffretail.verifone.fetch;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.handsoffretail.auth.client.CommonErrorCode;
import com.handsoffretail.auth.client.DomainException;
import com.handsoffretail.auth.client.HorPrincipal;
import com.handsoffretail.verifone.common.PageResult;
import com.handsoffretail.verifone.common.Paging;
import com.handsoffretail.verifone.stores.StoreDirectory;

@RestController
@RequestMapping("/api/ingestion/verifone/fetches")
class FetchController {

    private final FetchService fetches;
    private final FetchQueryService queries;
    private final StoreDirectory stores;

    FetchController(FetchService fetches, FetchQueryService queries, StoreDirectory stores) {
        this.fetches = fetches;
        this.queries = queries;
        this.stores = stores;
    }

    @PostMapping
    ResponseEntity<Map<String, Long>> start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StartFetchRequest request) {
        HorPrincipal caller = HorPrincipal.from(jwt);
        stores.require(caller, request.storeId());
        long id = fetches.start(request.storeId(), request.businessDate(), request.mode(), caller.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("fetchRequestId", id));
    }

    @GetMapping("/{id}")
    FetchDetailResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        FetchQueryService.Detail detail = queries.detail(id);
        stores.require(HorPrincipal.from(jwt), detail.request().storeId());
        return FetchDetailResponse.from(detail);
    }

    @GetMapping
    PageResult<FetchResponse> history(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long storeId, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        HorPrincipal caller = HorPrincipal.from(jwt);
        if (storeId != null) {
            stores.require(caller, storeId);
        } else if (!caller.allStores()) {
            // Only platform admins today; a store-scoped caller must name a store (later phase: filter instead).
            throw new DomainException(CommonErrorCode.FORBIDDEN, "storeId is required");
        }
        return queries.history(Optional.ofNullable(storeId), Optional.ofNullable(from), Optional.ofNullable(to),
                Paging.of(limit, offset)).map(FetchResponse::from);
    }

    record StartFetchRequest(@NotNull @Positive Long storeId, @NotNull LocalDate businessDate,
            @NotNull FetchRequest.Mode mode) {
    }

    record FetchResponse(long id, long storeId, LocalDate businessDate, FetchRequest.Mode mode, FetchRequest.Status status,
            String message, long requestedBy, Instant requestedAt, Instant finishedAt, Long durationMs) {

        static FetchResponse from(FetchRequest r) {
            return new FetchResponse(r.fetchRequestId(), r.storeId(), r.businessDate(), r.mode(), r.status(),
                    r.message().orElse(null), r.requestedBy(), r.requestedAt(), r.finishedAt().orElse(null),
                    r.finishedAt().map(f -> Duration.between(r.requestedAt(), f).toMillis()).orElse(null));
        }
    }

    record ZipResponse(String bucket, String key, int version, String etag, long sizeBytes, Instant lastModified,
            Instant manifestCreatedAt, String mirrorVersion) {
    }

    record CheckResponse(String name, BigDecimal expected, BigDecimal actual, boolean passed, boolean hard, String detail) {
    }

    record FileResponse(long cpjFileId, String filePath, int daySeq, Instant loadedAt, Map<String, Long> rows,
            List<CheckResponse> checks) {
    }

    record FetchDetailResponse(FetchResponse fetch, ZipResponse zip, List<FileResponse> files) {

        static FetchDetailResponse from(FetchQueryService.Detail d) {
            ZipResponse zip = d.zip().map(z -> new ZipResponse(z.bucket(), z.key(), z.versionNo(), z.etag(), z.sizeBytes(),
                    z.lastModified(), z.manifestCreatedAt(), z.mirrorVersion())).orElse(null);
            List<FileResponse> files = d.files().stream().map(f -> new FileResponse(f.cpjFileId(), f.filePath(), f.daySeq(),
                    f.loadedAt(), f.rows(), f.checks().stream().map(c -> new CheckResponse(c.name(), c.expected().orElse(null),
                            c.actual().orElse(null), c.passed(), c.hard(), c.detail())).toList())).toList();
            return new FetchDetailResponse(FetchResponse.from(d.request()), zip, files);
        }
    }
}
```

### 4.5 `$J/fetch/FetchQueryService.java`
```java
package com.handsoffretail.verifone.fetch;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.handsoffretail.verifone.common.PageResult;
import com.handsoffretail.verifone.common.Paging;
import com.handsoffretail.verifone.common.VerifoneErrorCode;

/** Use cases: a fetch's detail (zip, files, rows per kind, load checks) and the fetch history. */
@Service
public class FetchQueryService {

    private final FetchRepository fetches;
    private final FetchQueries queries;

    public FetchQueryService(FetchRepository fetches, FetchQueries queries) {
        this.fetches = fetches;
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public Detail detail(long fetchRequestId) {
        FetchRequest request = fetches.find(fetchRequestId)
                .orElseThrow(() -> VerifoneErrorCode.notFound("fetch", fetchRequestId));
        return new Detail(request, request.s3ObjectId().flatMap(queries::zip), queries.files(fetchRequestId));
    }

    @Transactional(readOnly = true)
    public PageResult<FetchRequest> history(Optional<Long> storeId, Optional<LocalDate> from, Optional<LocalDate> to,
            Paging paging) {
        return queries.history(storeId, from, to, paging);
    }

    /** A fetch with what it loaded. */
    public record Detail(FetchRequest request, Optional<FetchQueries.Zip> zip, List<FetchQueries.LoadedFile> files) {

        public Detail {
            files = List.copyOf(files);
        }
    }
}
```

### 4.6 `$J/fetch/FetchService.java`
```java
package com.handsoffretail.verifone.fetch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.handsoffretail.verifone.common.VerifoneErrorCode;
import com.handsoffretail.verifone.config.IngestionProperties;
import com.handsoffretail.verifone.load.DayLoader;
import com.handsoffretail.verifone.load.LoadedDays;
import com.handsoffretail.verifone.source.ZipObject;
import com.handsoffretail.verifone.source.ZipSource;
import com.handsoffretail.verifone.stores.StoreDirectory;
import com.handsoffretail.verifone.stores.StoreRef;
import com.handsoffretail.verifone.verify.ManifestVerifier;
import com.handsoffretail.verifone.verify.VerifiedZip;

/**
 * The fetch of one business day:
 * claim (own transaction) → find and download the zip and verify it (no transaction, no locks) →
 * one load transaction (REFUSE / REPLACE, rows, load checks, LOADED) → or FAILED in its own
 * transaction. This class owns those boundaries and nothing else.
 */
@Service
public class FetchService {

    private final StoreDirectory stores;
    private final FetchRepository fetches;
    private final FetchStatusRecorder recorder;
    private final FetchWorker worker;
    private final ZipSource source;
    private final ManifestVerifier manifestVerifier;
    private final DayLoader loader;
    private final LoadedDays loadedDays;
    private final TransactionTemplate transactions;
    private final Path workDir;
    private final Clock clock;

    public FetchService(StoreDirectory stores, FetchRepository fetches, FetchStatusRecorder recorder, FetchWorker worker,
            ZipSource source, ManifestVerifier manifestVerifier, DayLoader loader, LoadedDays loadedDays,
            TransactionTemplate transactions, IngestionProperties.Storage storage, Clock clock) {
        this.stores = stores;
        this.fetches = fetches;
        this.recorder = recorder;
        this.worker = worker;
        this.source = source;
        this.manifestVerifier = manifestVerifier;
        this.loader = loader;
        this.loadedDays = loadedDays;
        this.transactions = transactions;
        this.workDir = storage.workDir();
        this.clock = clock;
    }

    /** Claims the day (409 if a fetch of it is running) and starts the fetch in the background. */
    public long start(long storeId, LocalDate businessDate, FetchRequest.Mode mode, long adminId) {
        StoreRef store = stores.find(storeId).orElseThrow(() -> VerifoneErrorCode.notFound("store", storeId));
        if (!store.isVerifone()) {
            throw VerifoneErrorCode.STORE_NOT_VERIFONE.exception("store " + store.storeCode() + " is not a Verifone store");
        }
        if (!store.active()) {
            throw VerifoneErrorCode.STORE_INACTIVE.exception("store " + store.storeCode() + " is inactive");
        }
        if (businessDate.isAfter(LocalDate.now(clock.withZone(store.timezone())))) {
            throw VerifoneErrorCode.DATE_IN_FUTURE.exception("the business date is in the future");
        }
        long fetchId = recorder.claim(store, businessDate, mode, adminId);
        worker.submit(fetchId, () -> process(fetchId));
        return fetchId;
    }

    /** Runs a claimed fetch to its end state (called on the fetch executor). */
    void process(long fetchId) {
        FetchRequest request = fetches.find(fetchId).orElseThrow(() -> VerifoneErrorCode.notFound("fetch", fetchId));
        StoreRef store = stores.find(request.storeId())
                .orElseThrow(() -> VerifoneErrorCode.notFound("store", request.storeId()));
        LocalDate day = request.businessDate();
        if (request.mode() == FetchRequest.Mode.REFUSE && loadedDays.isLoaded(store.storeId(), day)) {
            recorder.end(fetchId, FetchRequest.Status.REFUSED, day + " is already loaded (mode REFUSE); nothing changed");
            return;
        }
        Optional<ZipObject> zip = source.findLatestCpjZip(store.s3Folder(), day);
        if (zip.isEmpty()) {
            recorder.end(fetchId, FetchRequest.Status.NOT_FOUND, "no CPJ zip in " + source.bucket() + " under "
                    + store.s3Folder() + day.getYear() + "/<month>/" + day + "/CPJ/");
            return;
        }
        Path file = download(fetchId, zip.get());
        try {
            VerifiedZip verified = manifestVerifier.verify(zip.get(), file, store.storeCode());
            DayLoader.Request load = new DayLoader.Request(fetchId, store, day, request.mode() == FetchRequest.Mode.REPLACE,
                    source.bucket(), zip.get(), verified);
            transactions.executeWithoutResult(status -> finish(fetchId, loader.load(load)));
        } finally {
            delete(file);
        }
    }

    /** In the load transaction: the end state commits together with the data (or not at all). */
    private void finish(long fetchId, DayLoader.Outcome outcome) {
        switch (outcome) {
            case DayLoader.Refused refused -> fetches.finish(fetchId, FetchRequest.Status.REFUSED, Optional.empty(),
                    refused.reason() + "; nothing changed", clock.instant());
            case DayLoader.Loaded loaded -> fetches.finish(fetchId, FetchRequest.Status.LOADED,
                    Optional.of(loaded.s3ObjectId()), summary(loaded), clock.instant());
        }
    }

    private static String summary(DayLoader.Loaded loaded) {
        StringBuilder text = new StringBuilder("loaded ").append(loaded.files().size()).append(" file(s): ");
        for (DayLoader.FileLoad file : loaded.files()) {
            long soft = file.checks().stream().filter(c -> !c.passed()).count();
            text.append(file.filePath().substring(file.filePath().lastIndexOf('/') + 1)).append(" (")
                    .append(file.rows().getOrDefault("cpj.tickets", 0)).append(" tickets")
                    .append(soft == 0 ? ", all checks passed" : ", " + soft + " soft check(s) failed").append(") ");
        }
        return text.toString().strip();
    }

    private Path download(long fetchId, ZipObject zip) {
        try {
            Files.createDirectories(workDir);
            Path file = workDir.resolve("fetch-" + fetchId + ".zip");
            Files.deleteIfExists(file);
            source.download(zip, file);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot prepare the work folder " + workDir, e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + file, e);
        }
    }
}
```

### 4.7 `$J/fetch/FetchStatusRecorder.java`
```java
package com.handsoffretail.verifone.fetch;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.handsoffretail.verifone.stores.StoreRef;

/**
 * The short transactions of a fetch, each committed on its own: the claim (RUNNING), and the end states written
 * outside the load transaction (NOT_FOUND, an early REFUSED, FAILED after a rolled-back load).
 * The store row already exists in master.stores (store sync), so the claim no longer copies it.
 */
@Service
public class FetchStatusRecorder {

    private final FetchRepository fetches;
    private final Clock clock;

    public FetchStatusRecorder(FetchRepository fetches, Clock clock) {
        this.fetches = fetches;
        this.clock = clock;
    }

    /** Inserts the RUNNING row. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long claim(StoreRef store, LocalDate businessDate, FetchRequest.Mode mode, long requestedBy) {
        return fetches.claim(store.storeId(), businessDate, mode, requestedBy, clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void end(long fetchRequestId, FetchRequest.Status status, String message) {
        fetches.finish(fetchRequestId, status, Optional.empty(), message, clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int failStale(Duration olderThan, String message) {
        return fetches.failStale(clock.instant().minus(olderThan), clock.instant(), message);
    }
}
```

### 4.8 `$J/fetch/FetchWorker.java`
```java
package com.handsoffretail.verifone.fetch;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.handsoffretail.verifone.common.VerifoneErrorCode;

/**
 * Runs fetches on the small fetch executor. The only place that catches every exception:
 * whatever ends a fetch abnormally is recorded as FAILED.
 */
@Component
class FetchWorker {

    private static final Logger log = LoggerFactory.getLogger(FetchWorker.class);

    private final Executor executor;
    private final FetchStatusRecorder recorder;

    FetchWorker(@Qualifier("fetchExecutor") Executor executor, FetchStatusRecorder recorder) {
        this.executor = executor;
        this.recorder = recorder;
    }

    void submit(long fetchRequestId, Runnable fetch) {
        try {
            executor.execute(() -> run(fetchRequestId, fetch));
        } catch (RejectedExecutionException e) {
            recorder.end(fetchRequestId, FetchRequest.Status.FAILED, "too many fetches waiting; try again in a minute");
            throw VerifoneErrorCode.FETCH_QUEUE_FULL.exception("too many fetches are waiting; try again in a minute");
        }
    }

    private void run(long fetchRequestId, Runnable fetch) {
        try {
            fetch.run();
        } catch (Exception e) {
            String message = FailureMessages.of(e);
            if (FailureMessages.isExpected(e)) {
                log.warn("Fetch {} failed: {}", fetchRequestId, message);
            } else {
                log.error("Fetch {} failed unexpectedly", fetchRequestId, e);
            }
            recorder.end(fetchRequestId, FetchRequest.Status.FAILED, message);
        }
    }
}
```

### 4.9 `$J/fetch/JdbcFetchRepository.java`

Only the imports and the `catch` block change. Apply these two edits to the copied file.

Imports, replace
```java
import com.handsoffretail.verifone.common.PostgresErrors;
import com.handsoff.hor.shared.error.ConflictException;
```
with
```java
import com.handsoffretail.verifone.common.PostgresErrors;
import com.handsoffretail.verifone.common.VerifoneErrorCode;
```

In `claim`, replace
```java
                throw new ConflictException("FETCH_RUNNING", "a fetch of this store and day is already running");
```
with
```java
                throw VerifoneErrorCode.FETCH_RUNNING.exception("a fetch of this store and day is already running");
```

### 4.10 Unchanged after the Step 1 sed

`FailureMessages`, `FetchQueries`, `JdbcFetchQueries`, `FetchRepository` (Javadoc fixed by sed), `config/FetchConfig`,
`VerifoneIngestionApplication`, and everything under `source/`, `parse/`, `load/`, `verify/`, plus `days/{DayQueries, JdbcDayQueries}`,
`departments/{Department, DepartmentRepository, DepartmentService, JdbcDepartmentRepository}`, `fetch/{FetchRequest, StaleFetchSweeper}`.

The `Clock` bean comes from auth-client (`horAuthClock`, `@ConditionalOnMissingBean`). Do not add another one.

**Check 4:**
```bash
grep -rn 'com\.handsoff\.hor' $SVC/src/main || echo "main clean"
cd $SVC && ./gradlew.bat compileJava
```
Both must pass. For the test sources, see Step 5.

---

## Step 5: Tests

### 5.1 Delete

Step 1 already deleted `TestJwt.java` and `TestPlatform.java`.

### 5.2 `$T/TestTokens.java` (new)
```java
package com.handsoffretail.verifone;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.handsoffretail.auth.client.HorJwtDecoderConfiguration;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Signs access tokens the way hor-auth does (RS256, issuer hor-auth), and the matching decoder for tests. */
public final class TestTokens {

    public static final String COOKIE = "hor_access";
    public static final String ORIGIN = "http://localhost:5182";

    private static final RSAKey KEY = generate();

    private TestTokens() {
    }

    public static JwtDecoder decoder() {
        try {
            return HorJwtDecoderConfiguration.create(KEY.toRSAPublicKey(), Clock.systemUTC());
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String platformAdmin(long adminId) {
        return sign(claims(adminId, "ADMIN", "PLATFORM_ADMIN").claim("all_stores", true).claim("stores", List.of()));
    }

    /** A valid token of a store user: authenticated, but not allowed here. */
    public static String clientUser(long userId, List<Long> stores) {
        return sign(claims(userId, "STORE_USER", "CLIENT_USER").claim("all_stores", false).claim("stores", stores));
    }

    private static JWTClaimsSet.Builder claims(long subject, String type, String role) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer("hor-auth").subject(String.valueOf(subject))
                .claim("typ", type).claim("role", role).claim("name", "Test " + role)
                .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))));
    }

    private static String sign(JWTClaimsSet.Builder claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("test").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

### 5.3 `$T/TestStores.java` (new; replaces `TestPlatform`)
```java
package com.handsoffretail.verifone;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A minimal auth_db {@code core.stores} inside verifone_db_test (the test profile points the auth pool there),
 * holding only the columns the sync reads. Ids are fixed so tests can name them.
 * The sync copies the VERIFONE rows into master.stores (IngestionIntegrationTest#syncStores).
 */
public final class TestStores {

    public static final long ADMIN_ID = 1;
    public static final long TIKI = 1;
    /** GILBARCO in auth_db: never copied, so 404 here. */
    public static final long GILBARCO_STORE = 2;
    public static final long INACTIVE_STORE = 3;

    private TestStores() {
    }

    public static void reset(JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA IF EXISTS core CASCADE");
        jdbc.execute("CREATE SCHEMA core");
        jdbc.execute("""
                CREATE TABLE core.stores (
                    store_id   bigint PRIMARY KEY,
                    store_code text   NOT NULL UNIQUE,
                    store_name text   NOT NULL,
                    pos_vendor text   NOT NULL,
                    s3_folder  text   NOT NULL UNIQUE,
                    timezone   text   NOT NULL,
                    status     text   NOT NULL)
                """);
        insert(jdbc, TIKI, "8730_IN-47872", "Tiki Hut", "VERIFONE", "Tiki_8730_IN-47872/Tiki_8730_IN-47872/", "ACTIVE");
        insert(jdbc, GILBARCO_STORE, "800_NY-13208", "Wolf", "GILBARCO", "wolf_800_NY-13208/800_NY-13208/", "ACTIVE");
        insert(jdbc, INACTIVE_STORE, "CLOSED_1", "Closed Store", "VERIFONE", "CLOSED_1/", "INACTIVE");
    }

    public static void insert(JdbcTemplate jdbc, long id, String code, String name, String vendor, String folder,
            String status) {
        jdbc.update("INSERT INTO core.stores VALUES (?, ?, ?, ?, ?, 'America/Indiana/Indianapolis', ?)",
                id, code, name, vendor, folder, status);
    }
}
```

### 5.4 `$T/IngestionIntegrationTest.java` (full replacement)
```java
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
```

### 5.5 `$T/IngestionApiTest.java` (full replacement)
```java
package com.handsoffretail.verifone;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The HTTP API with hor-auth cookie tokens and the CSRF header, over real HTTP. */
class IngestionApiTest extends IngestionIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE = "/api/ingestion/verifone";

    @LocalServerPort
    private int port;

    @Test
    void everyEndpointNeedsAPlatformAdmin() {
        assertThat(call(HttpMethod.GET, BASE + "/fetches", null, null).status()).isEqualTo(401);
        Response client = call(HttpMethod.GET, BASE + "/fetches", null, TestTokens.clientUser(5, List.of(TestStores.TIKI)));
        assertThat(client.status()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, BASE + "/stores/999/departments", null, admin()).status()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/actuator/health", null, admin()).status()).isIn(403, 404);
    }

    @Test
    void writesNeedTheCsrfHeaderAndAnAllowedOrigin() {
        Response noHeader = call(HttpMethod.POST, BASE + "/stores/sync", null, admin(), false);
        assertThat(noHeader.status()).isEqualTo(403);
        assertThat(noHeader.body().path("code").asString()).isEqualTo("CSRF_REJECTED");
        Response sync = call(HttpMethod.POST, BASE + "/stores/sync", null, admin());
        assertThat(sync.status()).isEqualTo(200);
        assertThat(sync.body().path("conflicts").isArray()).isTrue();
    }

    @Test
    void storesListsOnlySyncedVerifoneStores() {
        JsonNode stores = call(HttpMethod.GET, BASE + "/stores", null, admin()).body();
        List<String> codes = stores.valueStream().map(s -> s.path("storeCode").asString()).toList();
        assertThat(codes).contains("8730_IN-47872", "CLOSED_1").doesNotContain("800_NY-13208");
    }

    @Test
    void fetchThroughTheApiThenReadDetailDaysAndDepartments() {
        Response started = call(HttpMethod.POST, BASE + "/fetches",
                Map.of("storeId", TestStores.TIKI, "businessDate", "2026-09-08", "mode", "REPLACE"), admin());
        assertThat(started.status()).isEqualTo(202);
        long id = started.body().path("fetchRequestId").asLong();

        JsonNode detail = awaitDetail(id);
        assertThat(detail.path("fetch").path("status").asString()).isEqualTo("LOADED");
        assertThat(detail.path("zip").path("version").asInt()).isEqualTo(1);
        JsonNode file = detail.path("files").get(0);
        assertThat(file.path("rows").path("tickets").asLong()).isEqualTo(611);
        assertThat(file.path("checks").valueStream().filter(c -> c.path("hard").asBoolean())
                .allMatch(c -> c.path("passed").asBoolean())).isTrue();

        JsonNode available = call(HttpMethod.GET, BASE + "/stores/1/available-days?year=2026&month=9", null, admin()).body();
        assertThat(available.valueStream().map(JsonNode::asString).toList()).contains("2026-09-01", "2026-09-17");
        JsonNode loaded = call(HttpMethod.GET, BASE + "/stores/1/loaded-days?from=2026-09-01&to=2026-09-30", null, admin()).body();
        assertThat(loaded.valueStream().map(d -> d.path("businessDate").asString()).toList()).contains("2026-09-08");

        // The lottery class is fixed by the department name; nobody sets it.
        JsonNode departments = call(HttpMethod.GET, BASE + "/stores/1/departments", null, admin()).body();
        JsonNode lotto = departments.valueStream().filter(d -> d.path("deptNumber").asInt() == 29).findFirst().orElseThrow();
        assertThat(lotto.path("currentName").asString()).isEqualTo("Lotto");
        assertThat(lotto.path("names").get(0).path("lastSeen").asString()).isNotBlank();
        assertThat(lotto.path("lotteryClass").asString()).isEqualTo("LOTTERY_SALE");
        assertThat(departments.valueStream().filter(d -> d.path("deptNumber").asInt() == 28)
                .map(d -> d.path("lotteryClass").asString())).allMatch("LOTTERY_PAYOUT"::equals);
        assertThat(departments.valueStream().filter(d -> d.path("deptNumber").asInt() != 28 && d.path("deptNumber").asInt() != 29)
                .filter(d -> d.has("lotteryClass"))).isEmpty();

        Response history = call(HttpMethod.GET, BASE + "/fetches?storeId=1&limit=5", null, admin());
        assertThat(history.body().path("items").valueStream().map(f -> f.path("id").asLong()).toList()).contains(id);
    }

    @Test
    void theLotteryClassFollowsTheDepartmentName() {
        assertThat(lotteryClass("Lotto", "norm")).isEqualTo("LOTTERY_SALE");
        assertThat(lotteryClass("LOTTERY", "norm")).isEqualTo("LOTTERY_SALE");
        assertThat(lotteryClass("Lotto Payout", "neg")).isEqualTo("LOTTERY_PAYOUT");
        assertThat(lotteryClass("Lottery Payout", "norm")).isEqualTo("LOTTERY_PAYOUT");
        assertThat(lotteryClass("Lotto", "neg")).isEqualTo("LOTTERY_PAYOUT");
        assertThat(lotteryClass("DRINKS", "norm")).isNull();
        assertThat(lotteryClass("Bottle Return", "neg")).isNull();
    }

    private String lotteryClass(String name, String type) {
        return jdbc.queryForObject("SELECT master.lottery_class_for(?, ?)", String.class, name, type);
    }

    @Test
    void malformedRequestsAre400AndRuleBreaksAre422() {
        Response bad = call(HttpMethod.POST, BASE + "/fetches", Map.of("storeId", TestStores.TIKI, "mode", "REPLACE"), admin());
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().path("code").asString()).isEqualTo("VALIDATION_FAILED");
        Response future = call(HttpMethod.POST, BASE + "/fetches",
                Map.of("storeId", TestStores.TIKI, "businessDate", "2099-01-01", "mode", "REPLACE"), admin());
        assertThat(future.status()).isEqualTo(422);
        assertThat(future.body().path("code").asString()).isEqualTo("DATE_IN_FUTURE");
    }

    private JsonNode awaitDetail(long id) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            JsonNode detail = call(HttpMethod.GET, BASE + "/fetches/" + id, null, admin()).body();
            if (!detail.path("fetch").path("status").asString().equals("RUNNING")) {
                return detail;
            }
        }
        throw new AssertionError("fetch " + id + " did not end");
    }

    private static String admin() {
        return TestTokens.platformAdmin(TestStores.ADMIN_ID);
    }

    private Response call(HttpMethod method, String path, Object body, String token) {
        return call(method, path, body, token, true);
    }

    private Response call(HttpMethod method, String path, Object body, String token, boolean csrf) {
        RestClient.RequestBodySpec request = RestClient.create("http://localhost:" + port).method(method).uri(path);
        if (token != null) {
            request.header(HttpHeaders.COOKIE, TestTokens.COOKIE + "=" + token);
        }
        if (csrf) {
            request.header("X-HOR-Request", "1").header(HttpHeaders.ORIGIN, TestTokens.ORIGIN);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(body));
        }
        return request.exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            return new Response(res.getStatusCode().value(), bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes));
        });
    }

    private record Response(int status, JsonNode body) {
    }
}
```

### 5.6 `$T/stores/StoreSyncTest.java` (new)
```java
package com.handsoffretail.verifone.stores;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.handsoffretail.verifone.IngestionIntegrationTest;
import com.handsoffretail.verifone.TestStores;

/** The sync rules of VERIFONE_SERVICE_PLAN.md §6.2, on store ids 10/11 so other tests keep their stores. */
class StoreSyncTest extends IngestionIntegrationTest {

    @AfterEach
    void removeTestStores() {
        jdbc.update("DELETE FROM core.stores WHERE store_id IN (10, 11)");
    }

    @Test
    void insertsUpdatesDeactivatesAndReportsConflicts() {
        TestStores.insert(jdbc, 10, "SYNC_10", "Sync Ten", "VERIFONE", "SYNC_10/", "ACTIVE");
        StoreSyncService.Result first = storeSync.sync(TestStores.ADMIN_ID);
        assertThat(first.inserted()).isEqualTo(1);
        assertThat(status(10)).isEqualTo("ACTIVE");

        assertThat(storeSync.sync(TestStores.ADMIN_ID).updated()).isZero();

        jdbc.update("UPDATE core.stores SET store_name = 'Sync Ten B' WHERE store_id = 10");
        assertThat(storeSync.sync(TestStores.ADMIN_ID).updated()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT store_name FROM master.stores WHERE store_id = 10", String.class))
                .isEqualTo("Sync Ten B");

        jdbc.update("UPDATE core.stores SET store_code = 'SYNC_10X', s3_folder = 'SYNC_10X/' WHERE store_id = 10");
        StoreSyncService.Result mismatch = storeSync.sync(TestStores.ADMIN_ID);
        assertThat(mismatch.conflicts()).singleElement().satisfies(c -> assertThat(c.storeId()).isEqualTo(10));
        assertThat(jdbc.queryForObject("SELECT store_code FROM master.stores WHERE store_id = 10", String.class))
                .isEqualTo("SYNC_10");

        TestStores.insert(jdbc, 11, "SYNC_10", "Steals a code", "VERIFONE", "SYNC_11/", "ACTIVE");
        assertThat(storeSync.sync(TestStores.ADMIN_ID).conflicts()).extracting(StoreSyncService.Conflict::storeId)
                .contains(10L, 11L);

        jdbc.update("DELETE FROM core.stores WHERE store_id IN (10, 11)");
        assertThat(storeSync.sync(TestStores.ADMIN_ID).deactivated()).isEqualTo(1);
        assertThat(status(10)).isEqualTo("INACTIVE");
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM master.stores WHERE store_id = ?", String.class, id);
    }
}
```

> Note: store 10 stays INACTIVE in `master.stores` for the rest of the test run. That is harmless because no other test uses it.

### 5.7 `$T/fetch/FetchBehaviourTest.java`: three edits to the copied file

1. Imports: delete
   ```java
   import com.handsoff.hor.shared.error.ConflictException;
   import com.handsoff.hor.shared.error.DomainException;
   import com.handsoff.hor.shared.error.ErrorKind;
   ```
   and add
   ```java
   import com.handsoffretail.auth.client.DomainException;
   ```
2. In `twoConcurrentFetchesOfOneDayOneIsRefusedByTheRunningGuard`, replace
   ```java
        assertThat(refused).singleElement().isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.code()).isEqualTo("FETCH_RUNNING"));
   ```
   with
   ```java
        assertThat(refused).singleElement().isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo("FETCH_RUNNING"));
   ```
3. Replace the whole method `onlyActiveVerifoneStoresCanBeFetchedAndUnknownStoresAreNotFound` with
   ```java
    @Test
    void onlyActiveSyncedVerifoneStoresCanBeFetchedAndOthersAreNotFound() {
        // GILBARCO stores are never copied into master.stores, so they are simply unknown here.
        assertThatThrownBy(() -> fetches.start(TestStores.GILBARCO_STORE, SEP_01, FetchRequest.Mode.REPLACE, 1))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode().code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> fetches.start(TestStores.INACTIVE_STORE, SEP_01, FetchRequest.Mode.REPLACE, 1))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode().code()).isEqualTo("STORE_INACTIVE"));
        assertThatThrownBy(() -> fetches.start(999, SEP_01, FetchRequest.Mode.REPLACE, 1))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode().code()).isEqualTo("NOT_FOUND"));
    }
   ```

### 5.9 Test settings from `.env`: `TestEnv`, `TestData`, `S3ZipSourceContractTest`

`$T/TestEnv.java` (new)
```java
package com.handsoffretail.verifone;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * A test setting: the environment variable, else the same key in this service's {@code .env}
 * (Gradle runs tests in the project folder). Blank means not set.
 */
public final class TestEnv {

    private static final Properties DOT_ENV = load(Path.of(".env"));

    private TestEnv() {
    }

    public static Optional<String> get(String name) {
        String value = System.getenv(name);
        if (value == null) {
            value = DOT_ENV.getProperty(name);
        }
        return Optional.ofNullable(value).map(String::strip).filter(v -> !v.isEmpty());
    }

    private static Properties load(Path file) {
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return properties;
    }
}
```

`$T/TestData.java`: replace the whole `private static Optional<Path> root()` method with
```java
    private static Optional<Path> root() {
        String property = System.getProperty("hor.testdata.root");
        Optional<String> value = property != null ? Optional.of(property) : TestEnv.get("HOR_TESTDATA_ROOT");
        return value.filter(v -> !v.isBlank()).map(Path::of);
    }
```
Then fix its class Javadoc to say "(system property {@code hor.testdata.root}, or {@code HOR_TESTDATA_ROOT} in the
environment or {@code .env})". Remove any imports that are now unused.

`$T/source/S3ZipSourceContractTest.java`:
- Replace
  ```java
        Properties secrets = secrets();
        String key = secrets.getProperty("hor.aws.access-key-id");
        String secret = secrets.getProperty("hor.aws.secret-access-key");
  ```
  with
  ```java
        String key = TestEnv.get("HOR_AWS_ACCESS_KEY_ID").orElse(null);
        String secret = TestEnv.get("HOR_AWS_SECRET_ACCESS_KEY").orElse(null);
  ```
- Delete the `private static Properties secrets()` method.
- Add `import com.handsoffretail.verifone.TestEnv;` and remove the imports that are now unused.

### 5.8 Other copied tests

`GoldenDays`, `GoldenDayTest`, `SpotCheckTest`, `TestBucket`, `TestData`, `parse/*`, `source/*` and `verify/*` need nothing beyond the
Step 1 sed. Tests that need real CPJ data skip themselves when `HOR_TESTDATA_ROOT` is empty. That behaviour is unchanged (§5.9).

**Check 5:**
```bash
grep -rn 'com\.handsoff\.hor' $SVC/src || echo "all clean"
cd $SVC && ./gradlew.bat build
```
Both must be green. Record in `docs/BUILD_REPORT.md` how many tests ran, how many were skipped and why.

---

## Step 6: Run against the real bucket (after the owner's M1–M3)

```bash
# Terminal A: hor-auth must be running on 8091 (see hor-auth/docs/RUNNING_LOCALLY.md).
# Terminal B:
cd $SVC && ./gradlew.bat bootRun
```

Smoke test with curl (Git Bash). The admin email and password are `hor.auth.bootstrap.admin.*` in hor-auth's `%USERPROFILE%\.hor-stores\secrets.properties` (read them yourself; never echo them).
```bash
J=/tmp/hor.cookies; O='Origin: http://localhost:5182'; H='X-HOR-Request: 1'
curl -s -c $J -H "$H" -H "$O" -H 'Content-Type: application/json' \
     -d '{"email":"<admin email>","password":"<admin password>"}' http://127.0.0.1:8091/api/auth/login
curl -s -b $J -H "$H" -H "$O" -X POST http://127.0.0.1:8092/api/ingestion/verifone/stores/sync
curl -s -b $J http://127.0.0.1:8092/api/ingestion/verifone/stores
curl -s -b $J "http://127.0.0.1:8092/api/ingestion/verifone/stores/<tikiId>/available-days?year=2026&month=9"
curl -s -b $J -H "$H" -H "$O" -H 'Content-Type: application/json' \
     -d '{"storeId":<tikiId>,"businessDate":"2026-09-08","mode":"REFUSE"}' http://127.0.0.1:8092/api/ingestion/verifone/fetches
curl -s -b $J http://127.0.0.1:8092/api/ingestion/verifone/fetches/<fetchRequestId>
```

If the login sets the cookie with `Secure`, curl will not send it over http. In that case, read `hor_access` from the
login response's `Set-Cookie` header and send `-H "Cookie: hor_access=<value>"` instead.

**Done when:** the fetch reaches `LOADED`, every hard load check passes, and
`SELECT count(*) FROM cpj.tickets` in `verifone_db` is greater than 0.

## Step 7: Docs

- `docs/RUNNING_LOCALLY.md`: prerequisites, M1–M3, the run commands, the curl smoke test.
- `docs/BUILD_REPORT.md`: what was built, test counts, any deviation from this guide and why.
- [Verifon_DATABASE_DESIGN.md](Verifon_DATABASE_DESIGN.md) §2.2 point 3: the sync is `POST /api/ingestion/verifone/stores/sync` on this service, not an auth endpoint.

---

## Owner's manual steps

| # | Step |
|---|---|
| **M1** | **Done 2026-10-22.** `.env` holds the DB password and AWS keys (created from the old secrets files). |
| **M2** | **Done 2026-10-22.** `verifone_db` and `verifone_db_test` exist and `auth_svc` can log in. |
| **M3** | Make sure auth-service is running on 8091, and that Tiki Hut (`8730_IN-47872`, vendor VERIFONE, folder `Tiki_8730_IN-47872/Tiki_8730_IN-47872/`) exists in auth_db. |
| M4 (optional) | Golden tests on real data: set `HOR_TESTDATA_ROOT=<folder with the CPJ test days, forward slashes>` in `.env`. When it is empty, those tests skip. |
