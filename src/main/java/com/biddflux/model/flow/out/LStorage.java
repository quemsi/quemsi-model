package com.biddflux.model.flow.out;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;

import com.biddflux.commons.persistence.Views;
import com.biddflux.commons.util.Exceptions;
import com.biddflux.commons.util.FileNameUtil;
import com.biddflux.model.dto.DataType;
import com.biddflux.model.flow.DataPackage;
import com.biddflux.model.flow.DataPackageFile;
import com.biddflux.model.flow.Flow;
import com.biddflux.model.flow.retention.RetentionPolicy;
import com.fasterxml.jackson.annotation.JsonView;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LStorage extends  AbstractStorage{
	private boolean ready;
	@JsonView(Views.OnlyIdName.class)
	@Setter
	@Getter
	private LocalDrive localDrive;
	@Autowired
	private FileNameUtil util;
	// @Autowired
	// private DataFileServiceImpl dataFileService;
	private Path dirPath;
    @Setter
	@Getter
	private RetentionPolicy retentionPolicy;
	
	@Override
	public void init(Flow f) {
		super.init(f);
		dirPath = Paths.get(localDrive.getStorageRoot(), rootPath);
		if(!Files.exists(dirPath)) {
			try {
				log.info("creating folders {}", dirPath);
				Files.createDirectories(dirPath);
			} catch (IOException e) {
				throw Exceptions.server("cannot-initialize-output-folder").withExtra("dirPath", dirPath).get();
			}
		}
		ready = true;
	}

	@Override
	public void store(String dataName, List<DataPackage> dataPackages, Long version) {
		if(dataPackages.isEmpty()){
			throw Exceptions.badRequest("datapackages-empty").withExtra("versionId", version).get();
		}
		Path dataFolder = Path.of(this.dirPath.toString() ,dataName);
		if(!dataFolder.toFile().exists()){
			try {
				Files.createDirectories(dataFolder);
			} catch (IOException e) {
				throw Exceptions.server("io-exception").withCause(e).get();
			}
		}
		
		dataPackages.forEach(dp -> {
			log.debug("storin java.io.File file :{}", dp.getName());
			String destPath = dirPath + File.separator + dataName + File.separator + util.versionedFileName(dp.getName(), version);
			log.debug("destination :{}", destPath);
			localDrive.checkForCapacity(dp.getLength());
			try {
				FileUtils.copyInputStreamToFile(dp.getInputStream(), new File(destPath));
			} catch (IOException e) {
				throw Exceptions.server("error-in-storing-folder").withExtra("destPath", destPath).withCause(e).get();
			}
		});
		retentionPolicy.clear();
	}

	@Override
	public List<DataPackage> getDataPackage(String dataName, DataType type, Long version) throws IOException {
		File dataFile = new File(dirPath +  File.separator + dataName + File.separator + dataName + "-" + version + "." + type.getExt());
		return List.of(new DataPackageFile(dataFile, dataFile.length(), util.getFileType(dataFile.getName())));
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

	@JsonView(Views.BasicInfo.class)
	public long getUsedSize(){
        return 0L; //dataFileService.findSizeByDataStorage(name);
    }
	
	@Override
	public void fillDetails(Map<String, Object> props) {
		props.put("name", name);
		props.put("type", Storage.class.getSimpleName());
	}
}
