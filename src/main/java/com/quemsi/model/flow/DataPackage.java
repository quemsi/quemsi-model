package com.quemsi.model.flow;

import java.io.File;
import java.io.InputStream;

public interface DataPackage {
	String getName();
	void setName(String name);
	String getContentType();
	void setContentType(String contentType);
	File getFile(String destName);
	/** File backing if available; otherwise {@code null}. Does not rename. */
	default File asFile() {
		return null;
	}
	InputStream getInputStream();
	void clear();
	long getLength();
}
