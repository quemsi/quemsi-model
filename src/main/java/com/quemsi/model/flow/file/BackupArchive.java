package com.quemsi.model.flow.file;

import java.io.Closeable;
import java.io.InputStream;
import java.util.List;

/**
 * Lazy, seekable view over a single backup zip file.
 * Entries are opened on demand; callers must close returned streams.
 */
public interface BackupArchive extends Closeable {
    InputStream open(String entryName);

    boolean exists(String entryName);

    /** Entry names that start with {@code prefix}, in archive order. */
    List<String> list(String prefix);

    /**
     * Page entry names under {@code tables/{qualifiedName}/pages/}, sorted by page number.
     */
    List<String> listPageEntries(String qualifiedTableName);
}
