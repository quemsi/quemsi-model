package com.biddflux.model.flow.file;

import java.io.ByteArrayInputStream;
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
import org.springframework.beans.factory.annotation.Autowired;

import com.biddflux.commons.util.Exceptions;
import com.biddflux.commons.util.FileNameUtil;
import com.biddflux.commons.util.FileResource;
import com.biddflux.model.flow.AbstractStep;
import com.biddflux.model.flow.DataPackage;
import com.biddflux.model.flow.DataPackageFileResource;
import com.biddflux.model.flow.Flow;
import com.biddflux.model.flow.FlowContext;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Unzip extends AbstractStep {
    @Autowired
    private FileNameUtil util;

    @Override
    public void execute(FlowContext context) {
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
