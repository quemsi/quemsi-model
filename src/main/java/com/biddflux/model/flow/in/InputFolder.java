package com.biddflux.model.flow.in;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;

import com.biddflux.commons.util.FileNameUtil;
import com.biddflux.model.flow.DataPackageFile;
import com.biddflux.model.flow.FlowContext;
import com.biddflux.model.flow.file.ChecksumProducer;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InputFolder implements Source {
	@Setter
	private String path;
	@Setter
	private boolean changed;
	@Setter
	private Boolean zip;
	// @Setter
	// private CheckSumDb checksumDb;
	@Setter
	private ChecksumProducer checksumProducer;
	@Autowired
	private FileNameUtil util;
	@Override
	public void execute(FlowContext context) {
		try (Stream<Path> paths = Files.walk(Paths.get(path))) {
		    context.getDataPackages().addAll(paths
		        .filter(Files::isRegularFile)
		        .map(Path::toFile)
				.filter(f -> {
					if(!changed){
						return true;
					}
					String old = null; //checksumDb.fileChecksum(f.getAbsolutePath());
					try {
						return checksumProducer.isDifferent(f, old);
					}catch(Exception e) {
						log.error("error", e);
						return true;
					}
				})		
				.map(f -> new DataPackageFile(f, f.length(), util.getFileType(f.getName())))
		        .collect(Collectors.toList()));
		    log.debug("{} files is being processed", context.getDataPackages().size());
		} catch (IOException e) {
			log.error("error walking path", e);
		} 
	}
	@Override
	public void fillDetails(Map<String, Object> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("type", InputFolder.class.getSimpleName());
		props.put("path", path);
		props.put("changed", changed);
		props.put("zip", zip);
		
		steps.put("source", props);
	}
}
