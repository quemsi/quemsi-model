package com.biddflux.model.flow.in;

import java.io.IOException;
import java.util.Map;

import com.biddflux.commons.util.Exceptions;
import com.biddflux.model.dto.DataGroup;
import com.biddflux.model.dto.DataVersion;
import com.biddflux.model.flow.FlowContext;
import com.biddflux.model.flow.out.Storage;

import lombok.Getter;
import lombok.Setter;

public class StoredData implements Source{
	@Setter
	private Storage storage;
	@Getter
	@Setter
	private String version;
	@Getter
	@Setter
	private Map<String, String> tags;
	
	@Override
	public void execute(FlowContext context) {
		try {
			DataGroup data = context.getFlow().getData();
			DataVersion dataVersion = context.getDataVersion(); 
			if(dataVersion == null){
				throw Exceptions.notFound("version-not-found").withExtra("data", data.getName()).withExtra("version", version).withExtra("tags", tags).get();
			}
			context.setDataVersion(dataVersion);
			context.setDataPackages(storage.getDataPackage(data.getName(), data.getType(), dataVersion.getId()));
		} catch (IOException e) {
			throw Exceptions.server("io-exception").withCause(e).get();
		}
	}

	@Override
	public void fillDetails(Map<String, Object> details) {
		details.put("storage", storage.getName());
		details.put("version", version);
		details.put("tags", tags);
		details.put("type", StoredData.class.getSimpleName());
	}
}
