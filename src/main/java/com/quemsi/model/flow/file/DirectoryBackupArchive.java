package com.quemsi.model.flow.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.util.CommonHelpers;
import com.quemsi.model.util.QuemsiTemp;

/**
 * {@link BackupArchive} over a staging directory using the same entry-name layout as the zip.
 */
public class DirectoryBackupArchive implements BackupArchive {
    private final Path root;
    private final boolean deleteOnClose;

    public DirectoryBackupArchive(Path root, boolean deleteOnClose) {
        this.root = root;
        this.deleteOnClose = deleteOnClose;
    }

    public static DirectoryBackupArchive open(Path root) {
        return new DirectoryBackupArchive(root, false);
    }

    @Override
    public InputStream open(String entryName) {
        Path path = root.resolve(entryName);
        if (!Files.isRegularFile(path)) {
            throw Exceptions.notFound("archive-entry-not-found").withExtra("entry", entryName).get();
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw Exceptions.server("unable-to-open-archive-entry")
                .withExtra("entry", entryName)
                .withCause(e)
                .get();
        }
    }

    @Override
    public boolean exists(String entryName) {
        return Files.isRegularFile(root.resolve(entryName));
    }

    @Override
    public List<String> list(String prefix) {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return names;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String name = root.relativize(p).toString().replace('\\', '/');
                if (prefix == null || prefix.isEmpty() || name.startsWith(prefix)) {
                    names.add(name);
                }
            });
        } catch (IOException e) {
            throw Exceptions.server("unable-to-list-staging").withCause(e).get();
        }
        return names;
    }

    @Override
    public List<String> listPageEntries(String qualifiedTableName) {
        String prefix = CommonHelpers.tablePagesPrefix(qualifiedTableName);
        List<String> pages = list(prefix);
        pages.sort(Comparator.comparingInt(CommonHelpers::pageNumFromEntryName));
        return pages;
    }

    @Override
    public void close() {
        if (deleteOnClose) {
            QuemsiTemp.deleteRecursively(root);
        }
    }
}
