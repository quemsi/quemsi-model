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
public class DataPackageFile implements DataPackage {
	private File file;
	private String name;
	private long length;
	private String contentType;
	private boolean deleteOnClear;

	public DataPackageFile(String name, File file, long length, String contentType) {
		this(name, file, length, contentType, false);
	}

	public DataPackageFile(String name, File file, long length, String contentType, boolean deleteOnClear) {
		this.file = file;
		this.name = name;
		this.length = file != null ? file.length() : length;
		this.contentType = contentType;
		this.deleteOnClear = deleteOnClear;
	}

	@Override
	public File getFile(String destName) {
		return file;
	}

	@Override
	public File asFile() {
		return file;
	}

	@Override
	public void clear() {
		if (deleteOnClear && file != null) {
			boolean deleted = FileUtils.deleteQuietly(file);
			log.info("{} clear result {}", file, deleted);
		}
	}

	@Override
	public InputStream getInputStream() {
		try {
			return new FileInputStream(file);
		} catch (FileNotFoundException e) {
			throw Exceptions.notFound("file-not-found").withExtra("name", name).get();
		}
	}

	@Override
	public long getLength() {
		return file != null ? file.length() : length;
	}
}
