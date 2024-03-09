package com.biddflux.model.flow.out;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import com.biddflux.commons.persistence.Views;
import com.biddflux.commons.util.Exceptions;
import com.biddflux.commons.util.FileNameUtil;
import com.biddflux.commons.util.FileResource;
import com.biddflux.commons.util.StringUtils;
import com.biddflux.model.dto.DataType;
import com.biddflux.model.flow.DataPackage;
import com.biddflux.model.flow.DataPackageFileResource;
import com.biddflux.model.flow.Flow;
import com.biddflux.model.flow.retention.RetentionPolicy;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.services.drive.model.File;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Gstorage extends AbstractStorage {
	@Setter
	private File rootDir;
	// @Setter
	// @Autowired
	// private GoogleDriveManager googleDriveService;
	@Getter
	@Setter
	private GoogleDrive googleDrive;
    private boolean ready;
	@Setter
	@Autowired
	private FileNameUtil util;
    // @Autowired
	// private DataFileServiceImpl dataFileService;
	@Getter
	@Setter
	private RetentionPolicy retentionPolicy;

	@Override
	public boolean isReady() {
		return ready && googleDrive.isConnected();
	}

	@Override
	public boolean recordFiles() {
		return true;
	}
	
	@Override
    public void init(Flow f) {
    	super.init(f);
    	this.isReady();
		googleDrive.getConnectedFuture().thenAccept(gd -> {
            try {
				rootDir = googleDrive.getFile(rootPath);
				if(rootDir == null) {
		            initialize();
	            }
				this.ready = true;
			} catch(TokenResponseException e) {
				log.error("token error on initialize", e);
				log.error("status {}, {}", e.getStatusCode());
				log.error(e.getDetails().getError());
				log.error(e.getDetails().getErrorDescription());
				googleDrive.clearConnection();
				googleDrive.setError(StringUtils.stackTraceOf(e));
				// googleDriveService.connectToDrives();
			} catch (IOException e) {
				log.error("error initializing gstorage", e);
				googleDrive.setError(StringUtils.stackTraceOf(e));
			}
		});
    }
    
	@Override
	public void store(String dataName, List<DataPackage> dataPackages, Long version) {
		if(!this.isReady()) {
			throw Exceptions.auth("drive-not-connected").withExtra("name", googleDrive.getName())
				.withExtra("auhtUrl", googleDrive.getAuthUrl()).get();
		}
		if(dataPackages.isEmpty()){
			throw Exceptions.badRequest("datapackages-empty").withExtra("versionId", version).get();
		}
		
		try {
			if(!googleDrive.fileExists(dataName)){
				googleDrive.createFolder(dataName, rootDir);
			}
			File dataDir = googleDrive.getFile(dataName);
			dataPackages.forEach(dp -> {
				log.debug("storin java.io.File file :{}", dp.getName());
				
				String destPath = util.versionedFileName(dp.getName(), version);
				log.debug("destination :{}", destPath);
				try {
					googleDrive.upload(new GoogleInputStreamContent(destPath, util.getFileType(destPath), dp.getInputStream(), dp.getLength()), dataDir);
				} catch (IOException e) {
					throw Exceptions.server("error-in-storing-google-drive").withCause(e).get();
				}
			});
		} catch (IOException e) {
			throw Exceptions.server("error-in-storing-google-drive").withCause(e).get();
		}
		retentionPolicy.clear();
	}
	
	@Override
	public List<DataPackage> getDataPackage(String dataName, DataType type, Long version) throws IOException {
		String fileName = util.versionedFileName(dataName + "." + type.getExt(), version);
		log.info("fileName : {}", fileName);
		String targetFile = new StringBuilder(rootPath).append( "/").append(dataName).append("/").append(fileName).toString();
		log.info("targetFile : {}", targetFile);
		File file = googleDrive.getFile(targetFile);
		if(file == null){
			throw Exceptions.notFound("file-not-found").withExtra("targetFile", targetFile).withExtra("driveName", googleDrive.getName()).get();
		}
		FileResource fr = googleDrive.directDownload(file);
		DataPackage dpf = new DataPackageFileResource(fr);
		return List.of(dpf);
	}
	
	@JsonView(Views.BasicInfo.class)
	public long getUsedSize(){
        return 0L; //dataFileService.findSizeByDataStorage(name);
    }

	@Override
	public void deleteFile(String dir, String fileName) throws IOException{
		String targetFile = new StringBuilder(rootPath).append( "/").append(dir).append("/").append(fileName).toString();
		log.info("targetFile : {}", targetFile);
		File file = googleDrive.getFile(targetFile);
		if(file != null){
			googleDrive.delete(file);
		}
	}

	protected boolean initialize() throws IOException {
		if(!googleDrive.fileExists(rootPath)) {
			rootDir = googleDrive.createFolder(rootPath, null);
		}
		return true;
	}
	
	@Override
	public void fillDetails(Map<String, Object> props) {
		props.put("name", name);
		props.put("type", Storage.class.getSimpleName());
	}
}
