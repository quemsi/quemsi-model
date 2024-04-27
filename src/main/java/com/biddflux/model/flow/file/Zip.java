package com.biddflux.model.flow.file;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.io.IOUtils;

import com.biddflux.commons.util.BaseRuntimeException;
import com.biddflux.commons.util.Exceptions;
import com.biddflux.commons.util.FileResource;
import com.biddflux.model.flow.AbstractStep;
import com.biddflux.model.flow.DataPackageFileResource;
import com.biddflux.model.flow.Flow;
import com.biddflux.model.flow.FlowContext;

public class Zip extends AbstractStep {
    @Override
    public void execute(FlowContext context) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
            ZipArchiveOutputStream archive = new ZipArchiveOutputStream(output);) {
            archive.setMethod(ZipEntry.DEFLATED);
            archive.setLevel(Deflater.BEST_COMPRESSION);
            context.getDataPackages().forEach(Exceptions.wrapConsumer(dp -> {
                archive.putArchiveEntry(new ZipArchiveEntry(dp.getName()));
                IOUtils.copy(dp.getInputStream(), archive);
                archive.closeArchiveEntry();
            }));
            archive.finish();
            
            String fileName = context.getFlow().getData().getName() + ".zip";

            FileResource fileResource = new FileResource(null, fileName, fileName, "application/zip", false, output.size(), new ByteArrayInputStream(output.toByteArray()));
            context.setDataPackages(List.of(new DataPackageFileResource(fileResource)));
        } catch(Exception ex){
            Throwable cause = ex;
            if(ex instanceof BaseRuntimeException bre){
                cause = bre.getCause();
            }
            throw Exceptions.server("unable-zip-data-package").withExtra("dataName", context.getFlow().getData().getName()).withCause(cause).get();
        }
        executeNext(context);
    }

    @Override
    public void init(Flow f) {
        super.init(f);
        super.initNext(f);
    }

    @Override
	public void fillDetails(List<Map<String, Object>> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("type", Zip.class.getSimpleName());
		steps.add(props);
		super.fillDetails(steps);
	}
}
