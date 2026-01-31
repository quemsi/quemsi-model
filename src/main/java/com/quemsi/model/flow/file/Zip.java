package com.quemsi.model.flow.file;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.io.IOUtils;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.FileResource;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.DataPackageFileResource;
import com.quemsi.model.flow.FlowContext;

public class Zip extends AbstractStep {
    @Override
    public void execute(FlowContext context) {
		try (ByteArrayOutputStream output = new ByteArrayOutputStream();
            ZipArchiveOutputStream archive = new ZipArchiveOutputStream(output);) {
            archive.setMethod(ZipEntry.DEFLATED);
            archive.setLevel(Deflater.BEST_COMPRESSION);
            context.logStepInfo( context.getCurrentStep(), LogMessage.info("Zipping {} data packages", context.getDataPackages().size()));
            context.getDataPackages().forEach(Exceptions.wrapConsumer(dp -> {
                archive.putArchiveEntry(new ZipArchiveEntry(dp.getName()));
                IOUtils.copy(dp.getInputStream(), archive);
                archive.closeArchiveEntry();
            }));
            archive.finish();
            
            String fileName = context.getFlow().getData().getName() + ".zip";
            context.logStepInfo( context.getCurrentStep(), LogMessage.info("Zipped data package to {}", fileName));
            FileResource fileResource = new FileResource(null, fileName, fileName, "application/zip", false, output.size(), output.toByteArray());
            context.setDataPackages(List.of(new DataPackageFileResource(fileName, fileResource)));
        } catch(BaseRuntimeException bre) {
            throw Exceptions.server("unable-zip-data-package").withExtra("dataName", context.getFlow().getData().getName()).withCause(bre.getCause()).get();
        }catch(Exception ex){
            throw Exceptions.server("general-error-in-zip").withCause(ex).get();
        }
    }

    @Override
	public void fillDetails(List<Map<String, Object>> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("type", Zip.class.getSimpleName());
		steps.add(props);
	}
}
