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
