package com.quemsi.model.flow.out;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.quemsi.model.dto.DataFile;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.Flow;
import com.quemsi.model.flow.FlowContext;

public interface Storage{
	String getName();
	boolean recordFiles();
	String getRootPath();
	void init(Flow f);
	void store(FlowContext context, String dataName, List<DataPackage> dataPackages, Long version);
	List<DataPackage> getFiles(FlowContext context, List<DataFile> files) throws IOException;
	void deleteFile(String dir, String fileName)throws IOException;
	boolean isReady();
	void fillDetails(Map<String, Object> props);
}
