package com.quemsi.model.flow.in;

import java.io.IOException;
import java.util.Map;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.DataGroup;
import com.quemsi.model.dto.DataVersion;
import com.quemsi.model.flow.FlowContext;
import com.quemsi.model.flow.out.Storage;

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
			context.setDataPackages(storage.getFiles(context, dataVersion.getFiles()));
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
