pluginManagement {
    // Plugin versions live in gradle.properties.
    plugins {
        id("org.springframework.boot") version providers.gradleProperty("springBootVersion").get()
        id("io.spring.dependency-management") version providers.gradleProperty("dependencyManagementVersion").get()
    }
}

rootProject.name = "verifone-ingestion-service"
