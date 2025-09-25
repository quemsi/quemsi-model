package com.quemsi.model.flow;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

import org.apache.commons.io.FileUtils;

import com.quemsi.commons.util.Exceptions;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class DataPackageFile implements DataPackage{
	private File file;
	private String name;
	private long length;
	private String contentType;
	
	public DataPackageFile(String name, File file, long length, String contentType){
		this.file = file;
		this.name = name;
		this.length = file.length();
		this.contentType = contentType;
	}
	@Override
	public File getFile(String destName) {
		
		if(!file.getName().equals(destName)){
			file.renameTo(new File(destName));
		}
		return file;
	}
	@Override
	public void clear() {
		boolean deleted = FileUtils.deleteQuietly(file);
		log.info("{} clear result {}", file, deleted);
	}
	
	@Override
	public InputStream getInputStream() {
		try {
			return new FileInputStream(file);
		} catch (FileNotFoundException e) {
			throw Exceptions.notFound("file-not-found").withExtra("name", name).get();
		}
	}
}
