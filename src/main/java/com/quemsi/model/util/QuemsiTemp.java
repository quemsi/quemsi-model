package com.quemsi.model.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.quemsi.commons.util.Exceptions;

/**
 * Shared temp/staging paths for backup zip staging and cloud download spooling.
 */
public final class QuemsiTemp {
    private static final String PROP = "quemsi.temp-dir";

    private QuemsiTemp() {
    }

    public static Path baseDir() {
        String configured = System.getProperty(PROP);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("QUEMSI_TEMP_DIR");
        }
        if (configured == null || configured.isBlank()) {
            return Path.of(System.getProperty("java.io.tmpdir"), "quemsi");
        }
        return Path.of(configured);
    }

    public static Path createStagingDir(String purpose) {
        Path base = baseDir();
        try {
            Files.createDirectories(base);
            return Files.createTempDirectory(base, purpose + "-");
        } catch (IOException e) {
            throw Exceptions.server("unable-to-create-staging-dir")
                .withExtra("purpose", purpose)
                .withExtra("baseDir", base.toString())
                .withExtra("prop", System.getProperty(PROP))
                .withExtra("env", System.getenv("QUEMSI_TEMP_DIR"))
                .withCause(e)
                .get();
        }
    }

    public static Path spoolToTempFile(InputStream in, String prefix, String suffix) {
        try {
            Path base = baseDir();
            Files.createDirectories(base);
            Path temp = Files.createTempFile(base, prefix, suffix);
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            return temp;
        } catch (IOException e) {
            throw Exceptions.server("unable-to-spool-temp-file").withCause(e).get();
        }
    }

    public static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    /* best effort */
                }
            });
        } catch (IOException ignored) {
            /* best effort */
        }
    }
}
