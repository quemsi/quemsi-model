package com.quemsi.model.flow.out;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;

import com.quemsi.commons.util.CommonOps;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.FileNameUtil;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.dto.DataFile;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.DataPackageFile;
import com.quemsi.model.flow.Flow;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.FlowContext.LogWriter;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LStorage extends  AbstractStorage{
	private boolean ready;
	@Setter
	@Getter
	private LocalDrive localDrive;
	@Setter
	@Autowired
	private FileNameUtil util;
	private Path dirPath;
	@Setter
	@Getter
	private long capacity;
	@Setter
	@Getter
	private long usedSize;
    @Setter
	@Getter
	private String retentionPolicy;
	@Setter
	private LogWriter logWriter;
	@Setter
	private Long agentId;
	
	@Override
	public void init(Flow f) {
		super.init(f);
		dirPath = Paths.get(localDrive.getStorageRoot(), rootPath);
		if(!Files.exists(dirPath)) {
			try {
				logWriter.log(agentId, null, null, LogMessage.info("creating folders {}", dirPath));
				log.info("creating folders {}", dirPath);
				Files.createDirectories(dirPath);
			} catch (IOException e) {
				throw Exceptions.server("cannot-initialize-output-folder").withExtra("dirPath", dirPath).withCause(e).get();
			}
		}
		ready = true;
	}

	@Override
	public void store(FlowContext context, String dataName, List<DataPackage> dataPackages, Long version) {
		if(dataPackages.isEmpty()){
			throw Exceptions.badRequest("datapackages-empty").withExtra("versionId", version).get();
		}
		String senitized = CommonOps.sanitizePath(this.dirPath.toString());
		Path dataFolder = Path.of(senitized ,dataName);
		if(!dataFolder.toFile().exists()){
			try {
				context.logStepInfo(context.getCurrentStep(), LogMessage.info("creating folders {}", dataFolder));
				Files.createDirectories(dataFolder);
			} catch (IOException e) {
				throw Exceptions.server("io-exception").withCause(e).get();
			}
		}
		
		context.logStepInfo(context.getCurrentStep(), LogMessage.info("storing {} files", dataPackages.size()));
		dataPackages.forEach(dp -> {
			context.logStepInfo(context.getCurrentStep(), LogMessage.debug("storing java.io.File file :{}", dp.getName()));
			String destPath = senitized + File.separator + dataName + File.separator + util.versionedFileName(dp.getName(), version);
			context.logStepInfo(context.getCurrentStep(), LogMessage.debug("destination :{}", destPath));
			localDrive.checkForCapacity(dp.getLength());
			try {
				FileUtils.copyInputStreamToFile(dp.getInputStream(), new File(destPath));
			} catch (IOException e) {
				throw Exceptions.server("error-in-storing-folder").withExtra("destPath", destPath).withCause(e).get();
			}
		});
	}

	@Override
	public List<DataPackage> getFiles(FlowContext context, List<DataFile> files) throws IOException {
		return files.stream().peek(f -> context.logStepInfo(context.getCurrentStep(), LogMessage.info("adding {}", dirPath +  CommonOps.sanitizePath(File.separator + f.getDir() + File.separator + util.versionedFileName(f.getName(), f.getVersion()))))).map(f -> (DataPackage)new DataPackageFile(f.getName(), new File(CommonOps.sanitizePath(dirPath +  File.separator + f.getDir() + File.separator + util.versionedFileName(f.getName(), f.getVersion()))), f.getSize(), f.getContentType())).toList();
	}

	@Override
	public void deleteFile(String dir, String fileName) throws IOException{
		File dataFile = new File(dirPath + File.separator + dir + File.separator + fileName);
		if(dataFile.exists()){
			dataFile.delete();
		}
	}

	@Override
	public boolean isReady() {
		return ready;
	}

	@Override
	public void fillDetails(Map<String, Object> props) {
		props.put("name", name);
		props.put("type", Storage.class.getSimpleName());
	}
}
