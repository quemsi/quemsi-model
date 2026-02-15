package com.quemsi.model.flow.file;

import java.io.ByteArrayOutputStream;
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
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.DataPackageFileResource;
import com.quemsi.model.flow.FlowContext;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Unzip extends AbstractStep {
    @Setter
    private FileNameUtil util;

    @Override
    public void execute(FlowContext context) {
		try{
            context.logStepInfo( context.getCurrentStep(), LogMessage.info("Unzipping {} data packages", context.getDataPackages().size()));
			List<DataPackage> unzipped = context.getDataPackages().stream().flatMap(dp -> {
                try(ZipFile zipFile = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(dp.getInputStream().readAllBytes())).get()){
                    List<DataPackage> resultList = new LinkedList<>();
                    Iterator<ZipArchiveEntry> i = zipFile.getEntries().asIterator();
                    while (i.hasNext()) {
                        ZipArchiveEntry entry = i.next();
                        if (!zipFile.canReadEntryData(entry)) {
                            context.logStepWarn(context.getCurrentStep(), "unreadable arhive entry " + entry);
                            continue;
                        }
                        if (entry.isDirectory()) {
                            throw Exceptions.server("directory-support-not-implemented").withExtra("name", dp.getName()).get();
                        } else {
                            ByteArrayOutputStream os = new ByteArrayOutputStream();
                            IOUtils.copy(zipFile.getInputStream(entry), os);
                            FileResource fr = new FileResource(null, entry.getName(), entry.getName(), util.getFileType(entry.getName()), false, os.size(), os.toByteArray());
                            resultList.add(new DataPackageFileResource(entry.getName(),fr));
                        }
                    }
                    return resultList.stream();
                }catch(Exception e){
                    throw Exceptions.server("unable-to-unzip-data-package").withExtra("name", dp.getName()).withCause(e).get();
                }
            }).collect(Collectors.toList());
            context.setDataPackages(unzipped);
        }catch(BaseRuntimeException bre) {
			throw bre;
		}catch(Exception e) {
			throw Exceptions.server("general-error-in-unzip").withCause(e).get();
		}
    }

    @Override
	public void fillDetails(List<Map<String, Object>> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("type", Unzip.class.getSimpleName());
		steps.add(props);
	}
}
