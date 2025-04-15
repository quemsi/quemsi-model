package com.quemsi.model.flow.file;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.commons.io.IOUtils;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.FileNameUtil;
import com.quemsi.commons.util.FileResource;
import com.quemsi.model.dto.FlowExecution.FlowExecutionStep;
import com.quemsi.model.dto.FlowExecutionStatus;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.DataPackageFileResource;
import com.quemsi.model.flow.Flow;
import com.quemsi.model.flow.FlowContext;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Unzip extends AbstractStep {
    @Setter
    private FileNameUtil util;

    @Override
    public void execute(FlowContext context) {
        FlowExecutionStep fes = null;
		try{
            fes = flow.sendStepStarted(context.getExecution().getId(), "Unzip", this.ord , LocalDateTime.now());
			List<DataPackage> unzipped = context.getDataPackages().stream().flatMap(dp -> {
                try(ZipFile zipFile = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(dp.getInputStream().readAllBytes())).get()){
                    List<DataPackage> resultList = new LinkedList<>();
                    Iterator<ZipArchiveEntry> i = zipFile.getEntries().asIterator();
                    while (i.hasNext()) {
                        ZipArchiveEntry entry = i.next();
                        if (!zipFile.canReadEntryData(entry)) {
                            log.info("unreadable arhive entry {}", entry);
                            continue;
                        }
                        if (entry.isDirectory()) {
                            throw Exceptions.server("directory-support-not-implemented").withExtra("name", dp.getName()).get();
                        } else {
                            ByteArrayOutputStream os = new ByteArrayOutputStream();
                            IOUtils.copy(zipFile.getInputStream(entry), os);
                            FileResource fr = new FileResource(null, entry.getName(), entry.getName(), util.getFileType(entry.getName()), false, os.size(), new ByteArrayInputStream(os.toByteArray()));
                            resultList.add(new DataPackageFileResource(fr));
                        }
                    }
                    return resultList.stream();
                }catch(Exception e){
                    throw Exceptions.server("unable-to-unzip-data-package").withExtra("name", dp.getName()).withCause(e).get();
                }
            }).collect(Collectors.toList());
            context.setDataPackages(unzipped);
            flow.sendStepFinished(fes, FlowExecutionStatus.SUCCESS);
        }catch(BaseRuntimeException bre) {
			context.logError(fes, "Erro in Unzip step", bre);
			flow.sendStepFinished(fes, FlowExecutionStatus.FAILED);
			throw bre;
		}catch(Exception e) {
			context.logError(fes, "Unexpected expection in From step", e);
            flow.sendStepFinished(fes, FlowExecutionStatus.FAILED);
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
		props.put("type", Unzip.class.getSimpleName());
		steps.add(props);
		super.fillDetails(steps);
	}
}
