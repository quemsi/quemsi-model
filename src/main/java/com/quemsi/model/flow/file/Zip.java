package com.quemsi.model.flow.file;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
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
import com.quemsi.model.dto.FlowExecution.FlowExecutionStep;
import com.quemsi.model.dto.FlowExecutionStatus;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.DataPackageFileResource;
import com.quemsi.model.flow.Flow;
import com.quemsi.model.flow.FlowContext;

public class Zip extends AbstractStep {
    @Override
    public void execute(FlowContext context) {
        FlowExecutionStep fes = flow.sendStepStarted(context.getExecution().getId(), "Zip", this.ord , LocalDateTime.now());
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
            flow.sendStepFinished(fes, FlowExecutionStatus.SUCCESS);
        } catch(Exception ex){
            Throwable cause = ex;
            if(ex instanceof BaseRuntimeException bre){
                cause = bre.getCause();
            }
            context.logError(fes, "error in Zip", cause);
            flow.sendStepFinished(fes, FlowExecutionStatus.FAILED);
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
