package com.quemsi.model.flow;

import java.io.File;
import java.io.InputStream;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.file.BackupArchive;

/**
 * Lazy DataPackage backed by a single entry inside a {@link BackupArchive}.
 * Does not own the archive lifecycle — close the archive separately.
 */
public class DataPackageArchiveEntry implements DataPackage {
    private final BackupArchive archive;
    private String name;
    private String contentType;
    private final long length;

    public DataPackageArchiveEntry(BackupArchive archive, String entryName, String contentType, long length) {
        this.archive = archive;
        this.name = entryName;
        this.contentType = contentType;
        this.length = length;
    }

    public DataPackageArchiveEntry(BackupArchive archive, String entryName, String contentType) {
        this(archive, entryName, contentType, -1L);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    @Override
    public File getFile(String destName) {
        throw Exceptions.server("archive-entry-not-file-backed").withExtra("entry", name).get();
    }

    @Override
    public File asFile() {
        return null;
    }

    @Override
    public InputStream getInputStream() {
        return archive.open(name);
    }

    @Override
    public void clear() {
        /* archive owned by FlowContext / parent package */
    }

    @Override
    public long getLength() {
        return length;
    }
}
