package com.biddflux.model.flow.in;

import java.io.IOException;
import java.util.Map;

import com.biddflux.commons.util.Exceptions;
import com.biddflux.model.dto.DataGroup;
import com.biddflux.model.dto.DataVersion;
import com.biddflux.model.flow.FlowContext;
import com.biddflux.model.flow.out.Storage;

import lombok.Setter;

public class StoredData implements Source{
	@Setter
	private Storage storage;
	@Setter
	private String version;
	@Setter
	private Map<String, String> tags;
	//TODO: select version in api and execute in agent
	// @Autowired
	// private DataVersionServiceImpl dataVersionService;
	
	@Override
	public void execute(FlowContext context) {
		try {
			DataGroup data = context.getFlow().getData();
			Map<String, String> effectiveTags = tags;
			if(context.getTags() != null && !context.getTags().isEmpty()){
				effectiveTags = context.getTags();
			}
			DataVersion dataVersion = null; //dataVersionService.findVersionByTags(data.getName(), version, effectiveTags);
			if(dataVersion == null){
				throw Exceptions.notFound("version-not-found").withExtra("data", data.getName()).withExtra("version", version).withExtra("tags", tags).get();
			}
			context.setDataPackages(storage.getDataPackage(data.getName(), data.getType(), dataVersion.getId()));
			context.setDataVersion(dataVersion);
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
