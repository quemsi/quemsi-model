package com.biddflux.model.service;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.web.multipart.MultipartFile;

import com.biddflux.commons.util.Exceptions;
import com.biddflux.commons.util.StringUtils;

public class FileStorageServiceLocal implements FileStorageService {
	private Path fileStorageLocation;
	@Value("${file-storage-local.root-folder:}")
	private String rootFolder;
	
	
	@PostConstruct
	public void initialize() {
		this.fileStorageLocation = Paths.get(rootFolder).toAbsolutePath().normalize();
	}
	
	@Override
	public void storeFile(MultipartFile file, String dir, String name) {
		Path dirPath = Paths.get(fileStorageLocation.toString(), dir).toAbsolutePath().normalize();
		String fileName = StringUtils.isEmptyOrNull(name)?StringUtils.cleanPath(file.getOriginalFilename()):name;
        try {
        	Files.createDirectories(dirPath);
        } catch (Exception ex) {
            throw Exceptions.server("unable-to-create-folder").withCause(ex).withExtra("dir", dir).get();
        }
        try {
            if(fileName.contains("..")) {
                throw Exceptions.badRequest("invalid-file-name").withExtra("fileName", fileName).get();
            }
            Path targetLocation = dirPath.resolve(fileName);
            try {
            	Path targetFolder = targetLocation.getParent();
            	if(!Files.exists(targetFolder)) {
            		Files.createDirectories(targetLocation);
            	}
            } catch (Exception ex) {
            	throw Exceptions.server("unable-to-create-folder").withCause(ex).withExtra("dir", targetLocation).get();
            }
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw Exceptions.server("io-exception").withCause(ex).get();
        }
	}

	@Override
	public Resource loadFileAsResource(String dir, String fileName) {
		try {
			Path dirPath = Paths.get(dir).toAbsolutePath().normalize();
			Resource resource = new UrlResource(dirPath.resolve(fileName).normalize().toUri());
            if(resource.exists()) {
                return resource;
            } else {
                throw Exceptions.notFound("file-not-found").withExtra("dir", dir).withExtra("fileName", fileName).get();
            }
        } catch (MalformedURLException ex) {
            throw Exceptions.badRequest("unexpected-error").withCause(ex).withExtra("dir", dir).withExtra("fileName", fileName).get();
        }
	}

}
