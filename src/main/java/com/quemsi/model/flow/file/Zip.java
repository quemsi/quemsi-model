package com.quemsi.model.flow.file;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.io.IOUtils;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.DataPackageFile;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.util.QuemsiTemp;

public class Zip extends AbstractStep {
    @Override
    public void execute(FlowContext context) {
        Path zipPath = null;
        try {
            String fileName = context.getFlow().getData().getName() + ".zip";
            zipPath = QuemsiTemp.baseDir().resolve(fileName + "-" + System.nanoTime());
            Files.createDirectories(zipPath.getParent());

            context.logStepInfo(context.getCurrentStep(), LogMessage.info("Zipping backup to {}", zipPath));

            try (FileOutputStream fos = new FileOutputStream(zipPath.toFile());
                 BufferedOutputStream bos = new BufferedOutputStream(fos);
                 ZipArchiveOutputStream archive = new ZipArchiveOutputStream(bos)) {
                archive.setMethod(ZipEntry.DEFLATED);
                archive.setLevel(Deflater.BEST_COMPRESSION);

                Path stagingDir = context.getStagingDir();
                if (stagingDir != null && Files.isDirectory(stagingDir)) {
                    zipStagingTree(archive, stagingDir, context);
                } else {
                    zipDataPackages(archive, context);
                }
                archive.finish();
            }

            context.closeBackupArchiveQuietly();
            context.clearStagingDirQuietly();
            if (context.getDataPackages() != null) {
                context.getDataPackages().forEach(DataPackage::clear);
            }

            File zipFile = zipPath.toFile();
            DataPackageFile zipPackage = new DataPackageFile(fileName, zipFile, zipFile.length(), "application/zip", true);
            context.setDataPackages(List.of(zipPackage));
            context.logStepInfo(context.getCurrentStep(),
                LogMessage.info("Zipped backup {} ({} bytes)", fileName, zipFile.length()));
        } catch (BaseRuntimeException bre) {
            if (zipPath != null) {
                QuemsiTemp.deleteRecursively(zipPath);
            }
            throw Exceptions.server("unable-zip-data-package")
                .withExtra("dataName", context.getFlow().getData().getName())
                .withCause(bre.getCause() != null ? bre.getCause() : bre)
                .get();
        } catch (Exception ex) {
            if (zipPath != null) {
                QuemsiTemp.deleteRecursively(zipPath);
            }
            throw Exceptions.server("general-error-in-zip").withCause(ex).get();
        }
    }

    private void zipStagingTree(ZipArchiveOutputStream archive, Path stagingRoot, FlowContext context) throws IOException {
        try (Stream<Path> walk = Files.walk(stagingRoot)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            context.logStepInfo(context.getCurrentStep(), LogMessage.info("Zipping {} staging files", files.size()));
            for (Path file : files) {
                String entryName = stagingRoot.relativize(file).toString().replace('\\', '/');
                archive.putArchiveEntry(new ZipArchiveEntry(entryName));
                try (InputStream in = Files.newInputStream(file)) {
                    IOUtils.copy(in, archive);
                }
                archive.closeArchiveEntry();
            }
        }
    }

    private void zipDataPackages(ZipArchiveOutputStream archive, FlowContext context) {
        context.logStepInfo(context.getCurrentStep(),
            LogMessage.info("Zipping {} data packages", context.getDataPackages().size()));
        context.getDataPackages().forEach(Exceptions.wrapConsumer(dp -> {
            archive.putArchiveEntry(new ZipArchiveEntry(dp.getName()));
            try (InputStream in = dp.getInputStream()) {
                IOUtils.copy(in, archive);
            }
            archive.closeArchiveEntry();
            context.logStepInfo(context.getCurrentStep(), LogMessage.info("zipped {}", dp.getName()));
        }));
    }

    @Override
    public void fillDetails(List<Map<String, Object>> steps) {
        Map<String, Object> props = new HashMap<>();
        props.put("type", Zip.class.getSimpleName());
        steps.add(props);
    }
}
