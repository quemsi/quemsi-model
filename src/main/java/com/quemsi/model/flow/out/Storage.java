package com.quemsi.model.flow.out;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.quemsi.model.dto.DataFile;
import com.quemsi.model.dto.DataType;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.Flow;

public interface Storage{
	String getName();
	boolean recordFiles();
	String getRootPath();
	void init(Flow f);
	void store(String dataName, List<DataPackage> dataPackages, Long version);
	List<DataPackage> getDataPackage(String dataName, DataType type, Long version) throws IOException;
	List<DataPackage> getFiles(List<DataFile> files) throws IOException;
	void deleteFile(String dir, String fileName)throws IOException;
	boolean isReady();
	void fillDetails(Map<String, Object> props);
}
