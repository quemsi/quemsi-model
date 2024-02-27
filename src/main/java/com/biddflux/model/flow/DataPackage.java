package com.biddflux.model.flow;

import java.io.File;
import java.io.InputStream;

public interface DataPackage {
	String getName();
	void setName(String name);
	String getContentType();
	void setContentType(String contentType);
	File getFile(String destName);
	InputStream getInputStream();
	void clear();
	long getLength();
}
