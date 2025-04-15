package com.quemsi.model.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {
	 void storeFile(MultipartFile file, String dir, String name);
	 Resource loadFileAsResource(String dir, String fileName);
}
