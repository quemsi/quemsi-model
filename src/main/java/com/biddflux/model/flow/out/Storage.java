package com.biddflux.model.flow.out;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.biddflux.EnvironmentVars;
import com.biddflux.model.dto.DataType;
import com.biddflux.model.flow.DataPackage;
import com.biddflux.model.flow.Flow;

public interface Storage{
	String getName();
	boolean recordFiles();
	String getRootPath();
	void init(Flow f, EnvironmentVars env);
	void store(String dataName, List<DataPackage> dataPackages, Long version);
	List<DataPackage> getDataPackage(String dataName, DataType type, Long version) throws IOException;
	void deleteFile(String dir, String fileName)throws IOException;
	boolean isReady();
	void fillDetails(Map<String, Object> props);
}
