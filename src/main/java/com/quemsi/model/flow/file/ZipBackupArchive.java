package com.quemsi.model.flow.file;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.util.CommonHelpers;

import lombok.Getter;

public class ZipBackupArchive implements BackupArchive {
    @Getter
    private final File zipFile;
    private final ZipFile zip;
    private final boolean deleteOnClose;

    public ZipBackupArchive(File zipFile, boolean deleteOnClose) throws IOException {
        this.zipFile = zipFile;
        this.deleteOnClose = deleteOnClose;
        this.zip = ZipFile.builder().setFile(zipFile).get();
    }

    public static ZipBackupArchive open(File zipFile) throws IOException {
        return new ZipBackupArchive(zipFile, false);
    }

    public static ZipBackupArchive openOwned(File zipFile) throws IOException {
        return new ZipBackupArchive(zipFile, true);
    }

    @Override
    public InputStream open(String entryName) {
        ZipArchiveEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            throw Exceptions.notFound("archive-entry-not-found").withExtra("entry", entryName).get();
        }
        try {
            return zip.getInputStream(entry);
        } catch (IOException e) {
            throw Exceptions.server("unable-to-open-archive-entry")
                .withExtra("entry", entryName)
                .withCause(e)
                .get();
        }
    }

    @Override
    public boolean exists(String entryName) {
        return zip.getEntry(entryName) != null;
    }

    @Override
    public List<String> list(String prefix) {
        List<String> names = new ArrayList<>();
        Enumeration<ZipArchiveEntry> entries = zip.getEntries();
        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            if (prefix == null || prefix.isEmpty() || name.startsWith(prefix)) {
                names.add(name);
            }
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
    public void close() throws IOException {
        zip.close();
        if (deleteOnClose && zipFile != null) {
            // best-effort; caller may also clear via DataPackage
            //noinspection ResultOfMethodCallIgnored
            zipFile.delete();
        }
    }
}
