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
    // PostgresErrors (common/) reads the driver's PSQLException; the old shared library had the same compileOnly.
    compileOnly("org.postgresql:postgresql")
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
