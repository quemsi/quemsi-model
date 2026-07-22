package com.quemsi.model.flow.file;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.FileNameUtil;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.DataPackageArchiveEntry;
import com.quemsi.model.flow.DataPackageFile;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.util.CommonConstants;
import com.quemsi.model.util.QuemsiTemp;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Opens a backup zip as a {@link BackupArchive} without extracting entries to heap.
 * Exposes {@code db-model.json} as a lazy {@link DataPackageArchiveEntry}.
 */
@Slf4j
public class Unzip extends AbstractStep {
    @Setter
    private FileNameUtil util;

    @Override
    public void execute(FlowContext context) {
        try {
            context.logStepInfo(context.getCurrentStep(),
                LogMessage.info("Opening {} zip package(s) as backup archive", context.getDataPackages().size()));
            if (context.getDataPackages() == null || context.getDataPackages().isEmpty()) {
                throw Exceptions.notFound("unable-to-find-data-packages").get();
            }
            DataPackage zipPackage = context.getDataPackages().get(0);
            ResolvedZip resolved = resolveZipFile(zipPackage);

            context.closeBackupArchiveQuietly();
            ZipBackupArchive archive = new ZipBackupArchive(resolved.file(), resolved.deleteOnClose());

            if (!archive.exists(CommonConstants.DB_MODEL_FILE_NAME)) {
                archive.close();
                throw Exceptions.notFound("unable-to-find-db-model")
                    .withExtra("entry", CommonConstants.DB_MODEL_FILE_NAME)
                    .get();
            }

            /* Drop prior packages without deleting the zip we now own via the archive */
            for (DataPackage dp : context.getDataPackages()) {
                if (dp instanceof DataPackageFile dpf && dpf.getFile() != null
                        && dpf.getFile().equals(resolved.file())) {
                    dpf.setDeleteOnClear(false);
                }
                dp.clear();
            }

            context.setBackupArchive(archive);
            DataPackage dbModel = new DataPackageArchiveEntry(
                archive,
                CommonConstants.DB_MODEL_FILE_NAME,
                MediaType.APPLICATION_JSON_VALUE
            );
            context.setDataPackages(List.of(dbModel));
            context.logStepInfo(context.getCurrentStep(),
                LogMessage.info("Backup archive ready (lazy); db-model.json available"));
        } catch (BaseRuntimeException bre) {
            throw bre;
        } catch (Exception e) {
            throw Exceptions.server("general-error-in-unzip").withCause(e).get();
        }
    }

    private ResolvedZip resolveZipFile(DataPackage zipPackage) {
        File asFile = zipPackage.asFile();
        if (asFile != null && asFile.isFile()) {
            boolean deleteOnClose = zipPackage instanceof DataPackageFile dpf && dpf.isDeleteOnClear();
            return new ResolvedZip(asFile, deleteOnClose);
        }
        try (InputStream in = zipPackage.getInputStream()) {
            Path temp = QuemsiTemp.spoolToTempFile(in, "quemsi-restore-", ".zip");
            log.info("Spooled zip package {} to {}", zipPackage.getName(), temp);
            return new ResolvedZip(temp.toFile(), true);
        } catch (Exception e) {
            throw Exceptions.server("unable-to-materialize-zip").withCause(e).get();
        }
    }

    private record ResolvedZip(File file, boolean deleteOnClose) {
    }

    @Override
    public void fillDetails(List<Map<String, Object>> steps) {
        Map<String, Object> props = new HashMap<>();
        props.put("type", Unzip.class.getSimpleName());
        steps.add(props);
    }
}
